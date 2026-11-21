package com.acme.ecommerce.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.cart.api.dto.CartItemResponse;
import com.acme.ecommerce.cart.api.dto.CartResponse;
import com.acme.ecommerce.cart.api.dto.ConsumedLinesResponse;
import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.order.api.dto.OrderLineRequest;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest.PaymentMethodRequest;
import com.acme.ecommerce.order.client.CartFeignClient;
import com.acme.ecommerce.order.client.CartInternalFeignClient;
import com.acme.ecommerce.order.client.InventoryFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.ProductBatchFeignClient;
import com.acme.ecommerce.order.client.ProductViewFeignClient.ProductView;
import com.acme.ecommerce.order.client.PromotionFeignClient;
import com.acme.ecommerce.order.client.UserFeignClient;
import com.acme.ecommerce.order.client.UserInternalFeignClient;
import com.acme.ecommerce.order.event.OrderEvent;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.service.OrderRemote;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * F-20261119-11 / TD-BE-03 with the real OrderRemote, outbox and relay (only the HTTP clients and
 * Kafka are mocked): a placed order's cart lines are cleared durably, and only those lines.
 */
class CartClearIntegrationTest extends AbstractOrderIntegrationTest {

  @MockitoBean private ProductBatchFeignClient products;
  @MockitoBean private UserFeignClient users;
  @MockitoBean private CartFeignClient carts;
  @MockitoBean private CartInternalFeignClient cartInternal;
  @MockitoBean private InventoryFeignClient inventory;
  @MockitoBean private PaymentFeignClient payments;
  @MockitoBean private PromotionFeignClient promotions;
  @MockitoBean private UserInternalFeignClient usersInternal;
  @MockitoBean private RiskService risks;
  @MockitoBean private KafkaTemplate<String, OrderEvent> kafka;

  @Autowired private OrderService orders;
  @Autowired private OutboxRelay relay;
  @Autowired private OrderRemote remote;
  @Autowired private OrderEventPublisher events;
  @Autowired private JobLock jobLock;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private JdbcTemplate jdbc;

  private static final Instant ADDED = Instant.parse("2026-11-20T10:00:00.123Z");

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
    when(cartInternal.consumed(any(), any())).thenReturn(new ConsumedLinesResponse(1));
    cartHolds(shirt, 2);
  }

  private void cartHolds(UUID product, int qty) {
    when(carts.getCart())
        .thenReturn(
            new CartResponse(
                List.of(
                    new CartItemResponse(
                        product,
                        qty,
                        "Shirt",
                        new BigDecimal("499.00"),
                        "INR",
                        null,
                        new BigDecimal("499.00").multiply(BigDecimal.valueOf(qty)),
                        ADDED,
                        null,
                        null,
                        null,
                        null)),
                "INR",
                1,
                new BigDecimal("998.00"),
                List.of()));
  }

  private OrderService.Placement placeFromCart(UUID who, String key) {
    return orders.place(
        who,
        "c@example.com",
        key,
        new PlaceOrderRequest(addressId, PaymentMethodRequest.CARD, null, null, null, null, null));
  }

  private List<String> clearRows(UUID orderId) {
    return jdbc.queryForList(
        "select status from orders.outbox where kind = 'CLEAR_CART' and idem_key = ?",
        String.class,
        "CLEAR_CART:" + orderId);
  }

  private void cartServiceDown() {
    reset(cartInternal);
    doThrow(new IllegalStateException("cart-service down"))
        .when(cartInternal)
        .consumed(any(), any());
  }

  @Test
  @DisplayName("AC-15: cart-service down at clear time: 201 and a durable PENDING CLEAR_CART row")
  void downAtClearTimeRecordsADurableRow() {
    cartServiceDown();

    OrderService.Placement p = placeFromCart(user, "down-1");

    assertThat(p.created()).isTrue();
    UUID orderId = p.order().id();
    assertThat(clearRows(orderId)).containsExactly("PENDING");
    verify(cartInternal, times(1)).consumed(eq(user), any());
    // The row is in the database, not in memory: a relay instance started afterwards sees it.
    // The bound: the first retry is due within the 240 s cap.
    assertThat(
            jdbc.queryForObject(
                "select extract(epoch from (next_attempt_at - now()))::bigint from orders.outbox"
                    + " where idem_key = ?",
                Long.class,
                "CLEAR_CART:" + orderId))
        .isLessThanOrEqualTo(240L);
  }

  @Test
  @DisplayName("AC-16: cart-service recovers: the relay clears the cart with no user action")
  void relayClearsAfterRecovery() {
    cartServiceDown();
    UUID orderId = placeFromCart(user, "down-2").order().id();
    assertThat(clearRows(orderId)).containsExactly("PENDING");

    reset(cartInternal);
    when(cartInternal.consumed(any(), any())).thenReturn(new ConsumedLinesResponse(1));
    jdbc.update(
        "update orders.outbox set next_attempt_at = now() where idem_key = ?",
        "CLEAR_CART:" + orderId);
    // A relay instance created after the order was placed (a restart) delivers it.
    new OutboxRelay(jdbcClient, remote, events, jobLock).relay();

    assertThat(clearRows(orderId)).containsExactly("DONE");
    verify(cartInternal, times(1))
        .consumed(
            eq(user),
            argThat(
                r ->
                    r.orderId().equals(orderId)
                        && r.lines().size() == 1
                        && r.lines().get(0).productId().equals(shirt)
                        && r.lines().get(0).quantity() == 2
                        && r.lines().get(0).addedAt().equals(ADDED)));
    // Once DONE a further pass does nothing; and repeating the clear by hand is harmless.
    relay.relay();
    // (Other tests' rows share this database, so count only this user's calls.)
    verify(cartInternal, times(1)).consumed(eq(user), any());
    remote.attemptClearCart(user, orderId, carts.getCart().items());
    assertThat(clearRows(orderId)).containsExactly("DONE");
  }

  @Test
  @DisplayName("an inline success marks the row DONE straight away")
  void inlineSuccessIsDone() {
    UUID orderId = placeFromCart(user, "ok-1").order().id();

    assertThat(clearRows(orderId)).containsExactly("DONE");
    verify(cartInternal, times(1)).consumed(eq(user), any());
  }

  @Test
  @DisplayName("AC-17: a pending clear, then checkout of the stale cart: no second order, cleared")
  void staleCartCheckoutAfterPendingClear() {
    cartServiceDown();
    UUID orderId = placeFromCart(user, "stale-1").order().id();
    assertThat(clearRows(orderId)).containsExactly("PENDING");

    reset(cartInternal);
    when(cartInternal.consumed(any(), any())).thenReturn(new ConsumedLinesResponse(1));
    assertThatThrownBy(() -> placeFromCart(user, "stale-2"))
        .isInstanceOf(CodedException.class)
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.RESOURCE_CONFLICT);

    assertThat(
            jdbc.queryForObject(
                "select count(*) from orders.orders where user_id = ?", Integer.class, user))
        .isEqualTo(1);
    // The replay enqueued for the same order (one row) and its inline retry cleared the cart.
    assertThat(clearRows(orderId)).containsExactly("DONE");
    verify(cartInternal, times(1)).consumed(eq(user), argThat(r -> r.orderId().equals(orderId)));
  }

  @Test
  @DisplayName("AC-18: the clear names only the lines the order consumed, never a later item")
  void clearCarriesOnlyTheConsumedLines() {
    UUID later = UUID.randomUUID();
    UUID orderId = placeFromCart(user, "scoped-1").order().id();
    // An item is added to the cart after the order was placed; the clear was built before it.
    cartHolds(later, 1);

    verify(cartInternal, times(1))
        .consumed(
            eq(user),
            argThat(
                r ->
                    r.orderId().equals(orderId)
                        && r.lines().size() == 1
                        && r.lines().get(0).productId().equals(shirt)));
    verify(cartInternal, never())
        .consumed(
            any(), argThat(r -> r.lines().stream().anyMatch(l -> l.productId().equals(later))));
  }

  @Test
  @DisplayName("AC-19: each user's clear is sent for that user and that order, never crossed")
  void clearIsPerUser() {
    UUID other = UUID.randomUUID();
    UUID orderA = placeFromCart(user, "a-1").order().id();
    UUID orderB = placeFromCart(other, "b-1").order().id();

    assertThat(orderA).isNotEqualTo(orderB);
    verify(cartInternal, times(1)).consumed(eq(user), argThat(r -> r.orderId().equals(orderA)));
    verify(cartInternal, times(1)).consumed(eq(other), argThat(r -> r.orderId().equals(orderB)));
    verify(cartInternal, never()).consumed(eq(user), argThat(r -> r.orderId().equals(orderB)));
    verify(cartInternal, never()).consumed(eq(other), argThat(r -> r.orderId().equals(orderA)));
  }

  @Test
  @DisplayName("AC-20: buy-now records no CLEAR_CART row and never calls cart-service")
  void buyNowDoesNotClearTheCart() {
    UUID orderId =
        orders
            .place(
                user,
                "c@example.com",
                "bn-1",
                new PlaceOrderRequest(
                    addressId,
                    PaymentMethodRequest.CARD,
                    null,
                    List.of(new OrderLineRequest(shirt, 1, null, null)),
                    null,
                    null,
                    null))
            .order()
            .id();

    assertThat(clearRows(orderId)).isEmpty();
    verifyNoInteractions(cartInternal);
    verify(carts, never()).getCart();
    verify(carts, never()).clearCart();
  }

  @Test
  @DisplayName("a WALLET order from the cart records the clear with the paid status")
  void walletCartOrderRecordsTheClear() {
    when(payments.walletBalance(user))
        .thenReturn(new PaymentFeignClient.WalletBalance(new BigDecimal("100000.00"), "INR"));
    cartServiceDown();

    OrderService.Placement p =
        orders.place(
            user,
            "c@example.com",
            "wallet-1",
            new PlaceOrderRequest(
                addressId, PaymentMethodRequest.CARD, null, null, true, null, null));

    assertThat(p.order().paymentMethod()).isEqualTo("WALLET");
    assertThat(clearRows(p.order().id())).containsExactly("PENDING");
  }
}
