package com.acme.ecommerce.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.order.api.dto.OrderLineRequest;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest.PaymentMethodRequest;
import com.acme.ecommerce.order.client.CartFeignClient;
import com.acme.ecommerce.order.client.InventoryFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.ProductBatchFeignClient;
import com.acme.ecommerce.order.client.ProductViewFeignClient.ProductView;
import com.acme.ecommerce.order.client.PromotionFeignClient;
import com.acme.ecommerce.order.client.UserFeignClient;
import com.acme.ecommerce.order.client.UserInternalFeignClient;
import com.acme.ecommerce.order.event.OrderEvent;
import com.acme.ecommerce.order.event.OrderEventType;
import com.acme.ecommerce.order.service.OrderLifecycleService;
import com.acme.ecommerce.order.service.OrderService;
import com.acme.ecommerce.order.service.RiskService;
import com.acme.ecommerce.order.support.AbstractOrderIntegrationTest;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * TD-P6-01 end to end with the real OrderRemote and publisher (only the HTTP clients and Kafka are
 * mocked): cancelling an order records its side effects in the outbox, and the relay delivers them.
 */
class OutboxEndToEndIntegrationTest extends AbstractOrderIntegrationTest {

  @MockitoBean private ProductBatchFeignClient products;
  @MockitoBean private UserFeignClient users;
  @MockitoBean private CartFeignClient carts;
  @MockitoBean private InventoryFeignClient inventory;
  @MockitoBean private PaymentFeignClient payments;
  @MockitoBean private PromotionFeignClient promotions;
  @MockitoBean private UserInternalFeignClient usersInternal;
  @MockitoBean private RiskService risks;
  @MockitoBean private KafkaTemplate<String, OrderEvent> kafka;

  @Autowired private OrderService orders;
  @Autowired private OrderLifecycleService lifecycle;
  @Autowired private OutboxRelay relay;
  @Autowired private JdbcTemplate jdbc;

  private final UUID user = UUID.randomUUID();
  private final UUID addressId = UUID.randomUUID();
  private final UUID shirt = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    when(users.findAddress(addressId))
        .thenReturn(
            new AddressResponse(
                addressId,
                "Home",
                "Test Customer",
                "+919999999999",
                "1 MG Road",
                null,
                "Bengaluru",
                "Karnataka",
                "560001",
                "IN",
                true,
                Instant.now(),
                Instant.now()));
    when(products.batch(anyString(), eq(true)))
        .thenReturn(
            List.of(
                new ProductView(
                    shirt,
                    "SKU-SHIRT",
                    "Shirt",
                    new BigDecimal("499.00"),
                    "INR",
                    true,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    null)));
    when(risks.assess(any(), any(), anyString()))
        .thenReturn(new RiskService.Assessment(0, List.of()));
    when(payments.create(any()))
        .thenReturn(
            new PaymentFeignClient.PaymentResult(
                "pay-1", null, "REQUIRES_PAYMENT", "SIMULATED", null, null));
    when(kafka.send(anyString(), anyString(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));
  }

  private List<String> kinds(UUID orderId) {
    return jdbc.queryForList(
        "select kind from orders.outbox where aggregate_id = ? order by seq",
        String.class,
        orderId);
  }

  @Test
  @DisplayName("cancelling an unpaid order records its side effects, and the relay delivers them")
  void cancelGoesThroughTheOutbox() {
    UUID orderId =
        orders
            .place(
                user,
                "c@example.com",
                "key-" + UUID.randomUUID(),
                new PlaceOrderRequest(
                    addressId,
                    PaymentMethodRequest.CARD,
                    null,
                    List.of(new OrderLineRequest(shirt, 2, null, null)),
                    null,
                    null,
                    null))
            .order()
            .id();

    lifecycle.cancelByUser(user, orderId);

    // Recorded, not yet called: nothing reaches another service until the relay runs.
    assertThat(kinds(orderId))
        .containsExactly(
            "CANCEL_PAYMENT", "RESTORE_STOCK", "RELEASE_DEALS", "RELEASE_EXCHANGE", "ORDER_EVENT");
    verify(inventory, never()).restore(any());

    relay.relay();

    verify(payments).cancelUnpaid(orderId);
    verify(inventory).restore(orderId);
    verify(promotions).releaseDeals(orderId);
    verify(promotions).releaseExchange(orderId);
    verify(kafka)
        .send(
            eq("order.events"),
            eq(orderId.toString()),
            argThat(
                e -> e.type() == OrderEventType.ORDER_CANCELLED && orderId.equals(e.orderId())));
    assertThat(
            jdbc.queryForList(
                "select distinct status from orders.outbox where aggregate_id = ?",
                String.class,
                orderId))
        .containsExactly("DONE");
  }
}
