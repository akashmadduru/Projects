package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.OrderLineRequest;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest.PaymentMethodRequest;
import com.acme.ecommerce.order.client.CartFeignClient;
import com.acme.ecommerce.order.client.InventoryFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient.PaymentResult;
import com.acme.ecommerce.order.client.ProductViewFeignClient;
import com.acme.ecommerce.order.client.ProductViewFeignClient.ProductView;
import com.acme.ecommerce.order.client.PromotionFeignClient;
import com.acme.ecommerce.order.client.UserFeignClient;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.support.AbstractOrderIntegrationTest;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Order placement against the real schema (TD-TEST-01): the money path, its idempotency and its
 * compensation when a later step fails. Every other service is mocked; the order store, pricing and
 * the database are real.
 */
class OrderPlacementIntegrationTest extends AbstractOrderIntegrationTest {

  @MockitoBean private ProductViewFeignClient products;
  @MockitoBean private UserFeignClient users;
  @MockitoBean private CartFeignClient carts;
  @MockitoBean private OrderRemote remote;
  @MockitoBean private RiskService risks;
  @MockitoBean private OrderEventPublisher events;

  @Autowired private OrderService orders;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OrderStore store;

  @Autowired private com.acme.ecommerce.order.web.InternalOrderLookupController lookup;

  private final UUID user = UUID.randomUUID();
  private final UUID addressId = UUID.randomUUID();
  private final UUID shirt = UUID.randomUUID();
  private final UUID mug = UUID.randomUUID();

  private static ProductView product(
      UUID id, String name, String price, String currency, boolean active) {
    return new ProductView(
        id,
        "SKU-" + name,
        name,
        new BigDecimal(price),
        currency,
        active,
        null,
        null,
        List.of(),
        List.of(),
        null);
  }

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
    when(products.findById(shirt)).thenReturn(product(shirt, "Shirt", "499.00", "INR", true));
    when(products.findById(mug)).thenReturn(product(mug, "Mug", "10.00", "USD", true));
    when(risks.assess(any(), any(), anyString()))
        .thenReturn(new RiskService.Assessment(0, List.of()));
    when(remote.reserveStock(any(), any(), anyString()))
        .thenAnswer(
            inv ->
                List.of(
                    new InventoryFeignClient.Allocation(
                        shirt,
                        null,
                        null,
                        UUID.fromString("eeeeeeee-0000-0000-0000-0000000000b1"),
                        "WH-BLR",
                        "560001",
                        2,
                        null)));
    when(remote.createPayment(any(), eq(user), any(), eq("INR"), eq("CARD")))
        .thenReturn(
            new PaymentResult("pay-1", null, "REQUIRES_PAYMENT", "RAZORPAY", null, "rzp_test"));
  }

  private PlaceOrderRequest buyNow(UUID productId, int qty) {
    return new PlaceOrderRequest(
        addressId,
        PaymentMethodRequest.CARD,
        null,
        List.of(new OrderLineRequest(productId, qty, null, null)),
        null,
        null,
        null);
  }

  private String status(UUID orderId) {
    return jdbc.queryForObject(
        "select status from orders.orders where id = ?", String.class, orderId);
  }

  @Test
  @DisplayName(
      "an offer line whose seller is unknown sends a null sellerId so inventory fails closed")
  void offerWithUnknownSellerSendsNullSellerId() {
    UUID offerId = UUID.randomUUID();
    ProductView base = product(shirt, "Shirt", "499.00", "INR", true);
    ProductViewFeignClient.Offer offer =
        new ProductViewFeignClient.Offer(
            offerId, null, null, null, new BigDecimal("499.00"), null, "INR", 1, 5);
    when(products.findById(shirt))
        .thenReturn(
            new ProductView(
                base.id(),
                base.sku(),
                base.name(),
                base.priceAmount(),
                base.priceCurrency(),
                base.active(),
                null,
                null,
                List.of(),
                List.of(offer),
                null));

    UUID owner = UUID.randomUUID();
    when(remote.reserveStock(any(), any(), anyString()))
        .thenReturn(
            List.of(
                new InventoryFeignClient.Allocation(
                    shirt, null, offerId, UUID.randomUUID(), "WH-X", "560001", 1, owner)));

    OrderService.Placement placed =
        orders.place(
            user,
            "c@example.com",
            "key-" + UUID.randomUUID(),
            new PlaceOrderRequest(
                addressId,
                PaymentMethodRequest.CARD,
                null,
                List.of(new OrderLineRequest(shirt, 1, null, offerId)),
                null,
                null,
                null));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<OrderRemote.StockLine>> lines = ArgumentCaptor.forClass(List.class);
    verify(remote).reserveStock(any(), lines.capture(), anyString());
    assertThat(lines.getValue())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.offerId()).isEqualTo(offerId);
              assertThat(l.sellerId()).isNull();
            });
  }

  @Test
  @DisplayName("a card order is saved awaiting payment, with stock reserved and a payment started")
  void cardOrderAwaitsPayment() {
    OrderService.Placement p =
        orders.place(user, "c@example.com", "key-" + UUID.randomUUID(), buyNow(shirt, 2));

    assertThat(p.created()).isTrue();
    assertThat(p.order().status()).isEqualTo("PENDING_PAYMENT");
    assertThat(p.order().subtotalAmount()).isEqualByComparingTo("998.00");
    assertThat(p.order().totalAmount()).isPositive();
    assertThat(p.order().payment()).isNotNull();
    assertThat(p.order().payment().paymentId()).isEqualTo("pay-1");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<OrderRemote.StockLine>> lines = ArgumentCaptor.forClass(List.class);
    verify(remote).reserveStock(eq(p.order().id()), lines.capture(), eq("560001"));
    assertThat(lines.getValue())
        .isNotEmpty()
        .allSatisfy(l -> assertThat(l.sellerId()).isEqualTo(Order.PLATFORM_SELLER));
    assertThat(status(p.order().id())).isEqualTo("PENDING_PAYMENT");
  }

  @Test
  @DisplayName("replaying the same idempotency key returns the same order and reserves stock once")
  void idempotentReplay() {
    String key = "key-" + UUID.randomUUID();

    OrderService.Placement first = orders.place(user, "c@example.com", key, buyNow(shirt, 2));
    OrderService.Placement again = orders.place(user, "c@example.com", key, buyNow(shirt, 2));

    assertThat(again.created()).isFalse();
    assertThat(again.order().id()).isEqualTo(first.order().id());
    verify(remote, times(1)).reserveStock(any(), any(), anyString());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from orders.orders where user_id = ?", Integer.class, user))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("admin list counts items with one grouped query; first-order lookup flips after")
  void adminListItemCountAndFirstOrder() {
    assertThat(lookup.firstOrder(user).firstOrder()).isTrue();

    OrderService.Placement p =
        orders.place(user, "c@example.com", "key-" + UUID.randomUUID(), buyNow(shirt, 2));

    var page =
        store.adminList(
            null, null, user, null, org.springframework.data.domain.PageRequest.of(0, 20));
    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).id()).isEqualTo(p.order().id());
    assertThat(page.getContent().get(0).itemCount()).isEqualTo(2);
    assertThat(lookup.firstOrder(user).firstOrder()).isFalse();
  }

  @Test
  @DisplayName("products in different currencies are refused before anything is saved")
  void currencyMix() {
    PlaceOrderRequest mixed =
        new PlaceOrderRequest(
            addressId,
            PaymentMethodRequest.CARD,
            null,
            List.of(
                new OrderLineRequest(shirt, 1, null, null),
                new OrderLineRequest(mug, 1, null, null)),
            null,
            null,
            null);

    assertThatThrownBy(() -> orders.place(user, "c@example.com", "key-mix", mixed))
        .isInstanceOf(CodedException.class)
        .satisfies(
            e ->
                assertThat(((CodedException) e).errorCode())
                    .isEqualTo(ErrorCode.CURRENCY_MISMATCH));
    verify(remote, never()).reserveStock(any(), any(), anyString());
  }

  @Test
  @DisplayName("an inactive product cannot be ordered")
  void inactiveProduct() {
    UUID retired = UUID.randomUUID();
    when(products.findById(retired)).thenReturn(product(retired, "Old", "5.00", "INR", false));

    assertThatThrownBy(() -> orders.place(user, "c@example.com", "key-old", buyNow(retired, 1)))
        .isInstanceOf(CodedException.class)
        .satisfies(
            e ->
                assertThat(((CodedException) e).errorCode())
                    .isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE));
  }

  @Test
  @DisplayName("if stock cannot be reserved the saved order is cancelled and the error surfaces")
  void stockFailureCancelsOrder() {
    when(remote.reserveStock(any(), any(), anyString()))
        .thenThrow(new IllegalStateException("out of stock"));

    assertThatThrownBy(() -> orders.place(user, "c@example.com", "key-stock", buyNow(shirt, 1)))
        .hasMessageContaining("out of stock");

    String status =
        jdbc.queryForObject(
            "select status from orders.orders where user_id = ? and idempotency_key = 'key-stock'",
            String.class,
            user);
    assertThat(status).isEqualTo("CANCELLED");
    verify(remote).restoreStockBestEffort(any());
  }

  @Test
  @DisplayName("if the card payment cannot be started the order is cancelled and stock restored")
  void paymentFailureUnwinds() {
    when(remote.createPayment(any(), any(), any(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("payment-service down"));

    assertThatThrownBy(() -> orders.place(user, "c@example.com", "key-pay", buyNow(shirt, 1)))
        .hasMessageContaining("payment-service down");

    UUID orderId =
        jdbc.queryForObject(
            "select id from orders.orders where user_id = ? and idempotency_key = 'key-pay'",
            UUID.class,
            user);
    assertThat(status(orderId)).isEqualTo("CANCELLED");
    verify(remote).restoreStockBestEffort(orderId);
    verify(remote, never()).redeemCoins(any(), anyInt(), any());
  }

  // ---- coupon, coins and wallet inside placement ----------------------------------------------

  private PlaceOrderRequest withExtras(String coupon, Boolean useWallet, Integer coins) {
    return new PlaceOrderRequest(
        addressId,
        PaymentMethodRequest.CARD,
        coupon,
        List.of(new OrderLineRequest(shirt, 2, null, null)),
        useWallet,
        coins,
        null);
  }

  @Test
  @DisplayName("a wallet that covers the whole order pays it: no card payment is started")
  void walletCoversWholeOrder() {
    when(remote.walletBalance(user))
        .thenReturn(new PaymentFeignClient.WalletBalance(new BigDecimal("100000.00"), "INR"));

    OrderService.Placement p =
        orders.place(user, "c@example.com", "key-wallet", withExtras(null, true, null));

    assertThat(p.order().paymentMethod()).isEqualTo("WALLET");
    assertThat(p.order().status()).isNotEqualTo("PENDING_PAYMENT");
    verify(remote)
        .debitWallet(
            eq(user),
            org.mockito.ArgumentMatchers.argThat(a -> a.compareTo(p.order().totalAmount()) == 0),
            eq("INR"),
            eq(p.order().id()));
    verify(remote, never()).createPayment(any(), any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("if the wallet cannot be charged the order is cancelled and stock restored")
  void walletFailureUnwinds() {
    when(remote.walletBalance(user))
        .thenReturn(new PaymentFeignClient.WalletBalance(new BigDecimal("100000.00"), "INR"));
    doThrow(new IllegalStateException("wallet down"))
        .when(remote)
        .debitWallet(any(), any(), anyString(), any());

    assertThatThrownBy(
            () ->
                orders.place(
                    user, "c@example.com", "key-wallet-fail", withExtras(null, true, null)))
        .hasMessageContaining("wallet down");

    UUID orderId =
        jdbc.queryForObject(
            "select id from orders.orders where user_id = ? and idempotency_key = 'key-wallet-fail'",
            UUID.class,
            user);
    assertThat(status(orderId)).isEqualTo("CANCELLED");
    verify(remote).restoreStockBestEffort(orderId);
    verify(remote).reverseWalletDebitDurable(eq(user), any(), eq("INR"), eq(orderId));
  }

  @Test
  @DisplayName("a valid coupon lowers the order and is redeemed against it")
  void couponApplied() {
    when(remote.validateCoupon(
            eq("SAVE50"), any(), eq("INR"), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(
            new PromotionFeignClient.Validation(
                "SAVE50", "50 off", new BigDecimal("50.00"), "INR"));

    OrderService.Placement p =
        orders.place(user, "c@example.com", "key-coupon", withExtras("save50", null, null));

    assertThat(p.order().couponCode()).isEqualTo("SAVE50");
    assertThat(p.order().discountAmount()).isEqualByComparingTo("50.00");
    verify(remote)
        .redeemCoupon(
            eq("SAVE50"),
            eq(user),
            eq(p.order().id()),
            any(),
            eq("INR"),
            org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  @DisplayName("if the coupon cannot be redeemed the order is cancelled and the coupon released")
  void couponRedeemFailureUnwinds() {
    when(remote.validateCoupon(
            eq("SAVE50"), any(), eq("INR"), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(
            new PromotionFeignClient.Validation(
                "SAVE50", "50 off", new BigDecimal("50.00"), "INR"));
    doThrow(new IllegalStateException("coupon used up"))
        .when(remote)
        .redeemCoupon(
            any(), any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());

    assertThatThrownBy(
            () ->
                orders.place(
                    user, "c@example.com", "key-coupon-fail", withExtras("SAVE50", null, null)))
        .hasMessageContaining("coupon used up");

    UUID orderId =
        jdbc.queryForObject(
            "select id from orders.orders where user_id = ? and idempotency_key = 'key-coupon-fail'",
            UUID.class,
            user);
    assertThat(status(orderId)).isEqualTo("CANCELLED");
    verify(remote).releaseCouponBestEffort(orderId);
  }

  @Test
  @DisplayName("if coins cannot be redeemed the order is cancelled and the coins released")
  void coinsRedeemFailureUnwinds() {
    doThrow(new IllegalStateException("coins down"))
        .when(remote)
        .redeemCoins(any(), anyInt(), any());

    assertThatThrownBy(
            () -> orders.place(user, "c@example.com", "key-coins-fail", withExtras(null, null, 10)))
        .hasMessageContaining("coins down");

    UUID orderId =
        jdbc.queryForObject(
            "select id from orders.orders where user_id = ? and idempotency_key = 'key-coins-fail'",
            UUID.class,
            user);
    assertThat(status(orderId)).isEqualTo("CANCELLED");
    verify(remote).releaseCoinsBestEffort(orderId);
  }

  @Test
  @DisplayName("coins above the per-order cap are refused before anything is saved")
  void coinsOverCap() {
    assertThatThrownBy(
            () -> orders.place(user, "c@example.com", "key-coins", withExtras(null, null, 100_000)))
        .hasMessageContaining("coins");
    verify(remote, never()).reserveStock(any(), any(), anyString());
  }

  // --- TD-BE-15: one cart, one order -------------------------------------------------------

  private void cartHolds(Instant addedAt) {
    when(carts.getCart())
        .thenReturn(
            new com.acme.ecommerce.cart.api.dto.CartResponse(
                List.of(
                    new com.acme.ecommerce.cart.api.dto.CartItemResponse(
                        shirt,
                        2,
                        "Shirt",
                        new BigDecimal("499.00"),
                        "INR",
                        null,
                        new BigDecimal("998.00"),
                        addedAt,
                        null,
                        null,
                        null,
                        null)),
                "INR",
                2,
                new BigDecimal("998.00"),
                List.of()));
  }

  private PlaceOrderRequest fromCart() {
    return new PlaceOrderRequest(
        addressId, PaymentMethodRequest.CARD, null, null, null, null, null);
  }

  private int orderCount() {
    return jdbc.queryForObject(
        "select count(*) from orders.orders where user_id = ?", Integer.class, user);
  }

  @Test
  @DisplayName("the same cart placed again with a new key is refused, and the clear is retried")
  void sameCartTwice() {
    cartHolds(Instant.parse("2026-11-18T10:00:00Z"));
    OrderService.Placement first = orders.place(user, "c@example.com", "tab-1", fromCart());

    assertThatThrownBy(() -> orders.place(user, "c@example.com", "tab-2", fromCart()))
        .isInstanceOf(CodedException.class)
        .hasMessageContaining(first.order().id().toString())
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    assertThat(orderCount()).isEqualTo(1);
    verify(carts, times(2)).clearCart();
    // Refused at the insert, before any stock was reserved for it.
    verify(remote, times(1)).reserveStock(any(), any(), anyString());
  }

  @Test
  @DisplayName("two checkouts of one cart at the same moment create one order")
  void concurrentCheckouts() throws Exception {
    cartHolds(Instant.parse("2026-11-18T11:00:00Z"));
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
      for (String key : List.of("race-a", "race-b")) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    orders.place(user, "c@example.com", key, fromCart());
                    return true;
                  } catch (CodedException e) {
                    return false;
                  }
                }));
      }
      start.countDown();
      long placed = 0;
      for (var r : results) {
        placed += r.get(30, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0;
      }
      assertThat(placed).isEqualTo(1);
      assertThat(orderCount()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("a cancelled order frees its cart, and items re-added after a clear are a new cart")
  void cancelledOrRefilledCartCanBeOrdered() {
    Instant added = Instant.parse("2026-11-18T12:00:00Z");
    cartHolds(added);
    OrderService.Placement first = orders.place(user, "c@example.com", "k-1", fromCart());
    jdbc.update("update orders.orders set status = 'CANCELLED' where id = ?", first.order().id());

    orders.place(user, "c@example.com", "k-2", fromCart());

    cartHolds(added.plusSeconds(60));
    orders.place(user, "c@example.com", "k-3", fromCart());
    assertThat(orderCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("the fingerprint ignores line order and needs every added-at instant")
  void fingerprint() {
    Instant t = Instant.parse("2026-11-18T09:00:00Z");
    var a = item(shirt, 1, t);
    var b = item(mug, 2, t.plusSeconds(1));
    assertThat(OrderService.cartFingerprint(List.of(a, b)))
        .isEqualTo(OrderService.cartFingerprint(List.of(b, a)))
        .hasSize(64);
    assertThat(OrderService.cartFingerprint(List.of(a, item(mug, 3, t.plusSeconds(1)))))
        .isNotEqualTo(OrderService.cartFingerprint(List.of(a, b)));
    assertThat(OrderService.cartFingerprint(List.of(a, item(mug, 2, null)))).isNull();
  }

  private static com.acme.ecommerce.cart.api.dto.CartItemResponse item(
      UUID productId, int qty, Instant addedAt) {
    return new com.acme.ecommerce.cart.api.dto.CartItemResponse(
        productId,
        qty,
        "x",
        BigDecimal.ONE,
        "INR",
        null,
        BigDecimal.ONE,
        addedAt,
        null,
        null,
        null,
        null);
  }
}
