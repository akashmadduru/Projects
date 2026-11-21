package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.client.ProductBatchFeignClient;
import com.acme.ecommerce.order.client.ProductViewFeignClient.ProductView;
import feign.FeignException;
import feign.Request;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The batched product lookup of {@code OrderService.draft} (TD-BE-06), without Spring. */
class OrderServiceFetchProductsTest {

  private final ProductBatchFeignClient batch = mock(ProductBatchFeignClient.class);
  private OrderService orders;

  @BeforeEach
  void setUp() {
    orders = new OrderService(null, null, null, batch, null, null, null, null, null, null);
  }

  private static ProductView view(UUID id) {
    return new ProductView(
        id, "SKU", "Name", new BigDecimal("1.00"), "INR", true, null, null, null, null, null);
  }

  private static Request request() {
    return Request.create(
        Request.HttpMethod.GET,
        "http://product-service/api/products/batch",
        Map.of(),
        null,
        StandardCharsets.UTF_8,
        null);
  }

  @Test
  @DisplayName("one request with the ids joined, detail=true, and a map keyed by id")
  void oneRequestForAllIds() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(batch.batch(anyString(), anyBoolean())).thenReturn(List.of(view(b), view(a)));

    Map<UUID, ProductView> out = orders.fetchProducts(List.of(a, b));

    verify(batch).batch(a + "," + b, true);
    assertThat(out).containsOnlyKeys(a, b);
    assertThat(out.get(a).id()).isEqualTo(a);
  }

  @Test
  @DisplayName("an id missing from the result is PRODUCT_UNAVAILABLE")
  void missingIdIsRefused() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(batch.batch(anyString(), eq(true))).thenReturn(List.of(view(a)));

    assertThatThrownBy(() -> orders.fetchProducts(List.of(a, b)))
        .isInstanceOf(CodedException.class)
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE);
  }

  @Test
  @DisplayName("a null or empty batch body is a missing id, not a NullPointerException")
  void nullBodyIsRefused() {
    UUID a = UUID.randomUUID();
    when(batch.batch(anyString(), eq(true))).thenReturn(null);

    assertThatThrownBy(() -> orders.fetchProducts(List.of(a)))
        .isInstanceOf(CodedException.class)
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE);
  }

  @Test
  @DisplayName("a Feign 5xx is DEPENDENCY_UNAVAILABLE (503); a 404 is PRODUCT_UNAVAILABLE")
  void feignErrorsAreMapped() {
    UUID a = UUID.randomUUID();
    when(batch.batch(anyString(), eq(true)))
        .thenThrow(new FeignException.ServiceUnavailable("down", request(), null, new HashMap<>()));
    assertThatThrownBy(() -> orders.fetchProducts(List.of(a)))
        .isInstanceOf(CodedException.class)
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);

    when(batch.batch(anyString(), eq(true)))
        .thenThrow(new FeignException.NotFound("nf", request(), null, new HashMap<>()));
    assertThatThrownBy(() -> orders.fetchProducts(List.of(a)))
        .isInstanceOf(CodedException.class)
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE);
  }
}
