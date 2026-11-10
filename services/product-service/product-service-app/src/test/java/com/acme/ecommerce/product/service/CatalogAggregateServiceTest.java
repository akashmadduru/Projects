package com.acme.ecommerce.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.inventory.api.dto.InventoryResponse;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.client.InventoryFeignClient;
import com.acme.ecommerce.product.web.dto.ProductCatalogAggregateResponse;
import feign.FeignException;
import feign.Request;
import feign.Request.HttpMethod;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.ConfigBuilder;

/**
 * Unit tests for the aggregate-read's own decisions.
 *
 * <p>No Spring context: {@link ProductCatalogService} and {@link InventoryFeignClient} are mocked,
 * backed by a REAL resilience4j {@code CircuitBreakerRegistry}/{@code CircuitBreaker} (via a
 * minimal {@code CircuitBreakerFactory} adapter, not a hand-rolled fake breaker) so {@code
 * aggregateCb}'s actual failure/fallback/open-circuit behaviour is under test, not just this
 * class's own call sequencing. The full HTTP round trip - including the case where
 * inventory-service is genuinely unreachable over the network - is covered by {@code
 * ProductEndpointsIntegrationTest}'s {@code AggregateEndpoint} nested class.
 */
@ExtendWith(MockitoExtension.class)
class CatalogAggregateServiceTest {

  private static final UUID PRODUCT_ID = UUID.randomUUID();

  @Mock private ProductCatalogService products;
  @Mock private InventoryFeignClient inventoryClient;

  private CatalogAggregateService service;

  @BeforeEach
  void setUp() {
    service =
        new CatalogAggregateService(products, inventoryClient, defaultCircuitBreakerFactory());
  }

  @Test
  @DisplayName("composes the product with its inventory row when the lookup succeeds")
  void composesProductAndInventoryWhenLookupSucceeds() {
    ProductResponse product = product();
    InventoryResponse inventory =
        new InventoryResponse(
            UUID.randomUUID(), PRODUCT_ID, 25, 0L, "WH-1", "Widget", "SKU-01", 10);
    when(products.findById(PRODUCT_ID)).thenReturn(product);
    when(inventoryClient.findByProductId(PRODUCT_ID)).thenReturn(inventory);

    ProductCatalogAggregateResponse response = service.getAggregate(PRODUCT_ID);

    assertThat(response.product()).isEqualTo(product);
    assertThat(response.inventory()).isEqualTo(inventory);
  }

  @Test
  @DisplayName(
      "degrades to inventory: null, not a thrown exception, when inventory-service answers 404"
          + " for a not-yet-provisioned product")
  void degradesToNullOnGenuine404() {
    when(products.findById(PRODUCT_ID)).thenReturn(product());
    when(inventoryClient.findByProductId(PRODUCT_ID)).thenThrow(notFoundException());

    ProductCatalogAggregateResponse response = service.getAggregate(PRODUCT_ID);

    assertThat(response.inventory()).isNull();
  }

  @Test
  @DisplayName(
      "degrades to inventory: null, not a thrown exception, on a timeout/5xx-shaped failure")
  void degradesToNullOnInfrastructureFailure() {
    when(products.findById(PRODUCT_ID)).thenReturn(product());
    when(inventoryClient.findByProductId(PRODUCT_ID)).thenThrow(gatewayTimeoutException());

    ProductCatalogAggregateResponse response = service.getAggregate(PRODUCT_ID);

    assertThat(response.inventory()).isNull();
  }

  @Test
  @DisplayName(
      "propagates RESOURCE_NOT_FOUND, and never calls inventory-service, when the product itself"
          + " does not exist")
  void propagatesProductNotFoundWithoutCallingInventory() {
    when(products.findById(PRODUCT_ID))
        .thenThrow(new ResourceNotFoundException("Product", PRODUCT_ID));

    assertThatThrownBy(() -> service.getAggregate(PRODUCT_ID))
        .isInstanceOf(ResourceNotFoundException.class);

    verifyNoInteractions(inventoryClient);
  }

  /**
   * A dedicated circuit-breaker test: proves {@code aggregateCb} degrades to {@code null} not only
   * when a single call fails, but when the breaker itself has TRIPPED OPEN from a run of failures -
   * the {@code CallNotPermittedException} case the fallback function must also absorb.
   */
  @Nested
  @DisplayName("aggregateCb - circuit-breaker-open behaviour")
  class CircuitBreakerOpenBehaviour {

    @Test
    @DisplayName(
        "once tripped open by repeated failures, further calls degrade to null without even"
            + " reaching the Feign client")
    void degradesToNullOnceCircuitIsOpen() {
      CatalogAggregateService tightlyConfiguredService =
          new CatalogAggregateService(products, inventoryClient, tightCircuitBreakerFactory());
      when(products.findById(PRODUCT_ID)).thenReturn(product());
      when(inventoryClient.findByProductId(PRODUCT_ID)).thenThrow(gatewayTimeoutException());

      // minimumNumberOfCalls=2, failureRateThreshold=50: two failing calls are enough for the
      // breaker's sliding window to evaluate and trip OPEN.
      assertThat(tightlyConfiguredService.getAggregate(PRODUCT_ID).inventory()).isNull();
      assertThat(tightlyConfiguredService.getAggregate(PRODUCT_ID).inventory()).isNull();

      // A long waitDurationInOpenState means the breaker stays OPEN for this third call - the
      // Feign client must not be invoked a third time; CallNotPermittedException is what the
      // fallback function absorbs instead.
      assertThat(tightlyConfiguredService.getAggregate(PRODUCT_ID).inventory()).isNull();
      verify(inventoryClient, times(2)).findByProductId(PRODUCT_ID);
    }
  }

  private static ProductResponse product() {
    return new ProductResponse(
        PRODUCT_ID,
        "SKU-01",
        "Widget",
        new BigDecimal("9.99"),
        "USD",
        true,
        0L,
        null,
        null,
        null,
        List.of(),
        null);
  }

  private static Request inventoryRequest() {
    return Request.create(
        HttpMethod.GET,
        "/api/inventory/by-product/" + PRODUCT_ID,
        Map.of(),
        (byte[]) null,
        (java.nio.charset.Charset) null);
  }

  private static FeignException.NotFound notFoundException() {
    return new FeignException.NotFound("not found", inventoryRequest(), null, null);
  }

  private static RetryableException gatewayTimeoutException() {
    return new RetryableException(
        504, "Gateway Timeout", HttpMethod.GET, (Long) null, inventoryRequest());
  }

  private static CircuitBreakerFactory<?, ?> defaultCircuitBreakerFactory() {
    return circuitBreakerFactoryOver(CircuitBreakerRegistry.ofDefaults());
  }

  /** A registry whose {@code aggregateCb} trips OPEN after exactly two failing calls. */
  private static CircuitBreakerFactory<?, ?> tightCircuitBreakerFactory() {
    CircuitBreakerConfig tight =
        CircuitBreakerConfig.custom()
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .build();
    // CircuitBreakerRegistry.of(CircuitBreakerConfig) installs the given config as the
    // registry's DEFAULT - the one every circuitBreaker(name) call uses absent a more specific
    // one - NOT keyed by breaker name (CircuitBreakerRegistry.of(Map<String, CircuitBreakerConfig>)
    // registers named CONFIG PROFILES, not per-instance configs, and would have left
    // aggregateCb on resilience4j's un-tight defaults here).
    return circuitBreakerFactoryOver(CircuitBreakerRegistry.of(tight));
  }

  /**
   * A minimal, real (not mocked) {@code CircuitBreakerFactory} backed directly by a resilience4j
   * {@code CircuitBreakerRegistry} - deliberately NOT {@code Resilience4JCircuitBreakerFactory},
   * whose constructor additionally requires a bulkhead provider that this test module does not have
   * {@code resilience4j-bulkhead} on its compile classpath for (the starter brings it in only at
   * runtime). This wrapper exercises the exact same underlying resilience4j {@code CircuitBreaker}
   * state machine {@code CatalogAggregateService}'s production wiring does - only the Spring Cloud
   * adapter construction differs.
   */
  private static CircuitBreakerFactory<?, ?> circuitBreakerFactoryOver(
      CircuitBreakerRegistry registry) {
    return new CircuitBreakerFactory<Object, ConfigBuilder<Object>>() {
      @Override
      public CircuitBreaker create(String id) {
        io.github.resilience4j.circuitbreaker.CircuitBreaker delegate = registry.circuitBreaker(id);
        return new CircuitBreaker() {
          @Override
          public <T> T run(Supplier<T> toRun, Function<Throwable, T> fallback) {
            try {
              return delegate.executeSupplier(toRun);
            } catch (Throwable throwable) {
              return fallback.apply(throwable);
            }
          }
        };
      }

      @Override
      protected ConfigBuilder<Object> configBuilder(String id) {
        throw new UnsupportedOperationException("not needed by this test's fixed registry");
      }

      @Override
      public void configureDefault(Function<String, Object> defaultConfiguration) {
        throw new UnsupportedOperationException("not needed by this test's fixed registry");
      }
    };
  }
}
