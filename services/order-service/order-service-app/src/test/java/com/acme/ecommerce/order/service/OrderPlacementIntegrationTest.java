package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.OrderLineRequest;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest.PaymentMethodRequest;
import com.acme.ecommerce.order.client.CartFeignClient;
import com.acme.ecommerce.order.client.DependencyBreakerCapability;
import com.acme.ecommerce.order.client.InventoryFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient.PaymentResult;
import com.acme.ecommerce.order.client.ProductBatchFeignClient;
import com.acme.ecommerce.order.client.ProductViewFeignClient.ProductView;
import com.acme.ecommerce.order.client.PromotionFeignClient;
import com.acme.ecommerce.order.client.RemoteCalls;
import com.acme.ecommerce.order.client.UserFeignClient;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.support.AbstractOrderIntegrationTest;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.codec.EncodeException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Order placement against the real schema (TD-TEST-01): the money path, its idempotency and its
 * compensation when a later step fails. Every other service is mocked; the order store, pricing and
 * the database are real.
 */
class OrderPlacementIntegrationTest extends AbstractOrderIntegrationTest {

  @MockitoBean private ProductBatchFeignClient products;

  /** The product-service catalog behind the batch stub: id to its detail view. */
  private final Map<UUID, ProductView> catalog = new HashMap<>();

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
    catalog.put(shirt, product(shirt, "Shirt", "499.00", "INR", true));
    catalog.put(mug, product(mug, "Mug", "10.00", "USD", true));
    when(products.batch(anyString(), eq(true))).thenAnswer(inv -> fromCatalog(inv.getArgument(0)));
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
                        2)));
    when(remote.createPayment(any(), eq(user), any(), eq("INR"), eq("CARD")))
        .thenReturn(
            new PaymentResult("pay-1", null, "REQUIRES_PAYMENT", "RAZORPAY", null, "rzp_test"));
  }

  /** What the batch endpoint returns: the known ids among those requested, in request order. */
  private List<ProductView> fromCatalog(String csv) {
    return java.util.Arrays.stream(csv.split(","))
        .map(UUID::fromString)
        .distinct()
        .map(catalog::get)
        .filter(java.util.Objects::nonNull)
        .toList();
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
    verify(remote).reserveStock(eq(p.order().id()), any(), eq("560001"));
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
    catalog.put(retired, product(retired, "Old", "5.00", "INR", false));

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
    // The clear is the durable, scoped one, and the refused retry re-asks for the SAME order.
    verify(remote, times(2)).clearCart(eq(user), eq(first.order().id()), any());
    verify(carts, never()).clearCart();
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

  // --- AC-2: a cart whose lines lack addedAt is refused, never placed unguarded ------------------

  @Test
  @DisplayName("a cart line without addedAt gives 503 and no order row, before any other call")
  void cartWithoutAddedAtIsRefused() {
    cartHolds(null);

    assertThatThrownBy(() -> orders.place(user, "c@example.com", "no-added-at", fromCart()))
        .isInstanceOf(CodedException.class)
        .hasMessage("Your cart could not be read, please try again")
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);

    assertThat(orderCount()).isZero();
    verify(remote, never()).reserveStock(any(), any(), anyString());
  }

  @Test
  @DisplayName("two different-key placements of a cart without addedAt both get 503; no order row")
  void racingCartsWithoutAddedAtCreateNoOrder() throws Exception {
    couponAndPartialWalletAvailable();
    cartHolds(null);
    PlaceOrderRequest withEverything =
        new PlaceOrderRequest(addressId, PaymentMethodRequest.CARD, "SAVE50", null, true, 10, null);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      List<java.util.concurrent.Future<ErrorCode>> results = new java.util.ArrayList<>();
      for (String key : List.of("nat-a", "nat-b")) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    orders.place(user, "c@example.com", key, withEverything);
                    return null;
                  } catch (CodedException e) {
                    return e.errorCode();
                  }
                }));
      }
      start.countDown();
      for (var r : results) {
        assertThat(r.get(30, java.util.concurrent.TimeUnit.SECONDS))
            .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(orderCount()).isZero();
    verify(remote, never()).validateCoupon(anyString(), any(), anyString(), anyBoolean());
    verify(remote, never()).reserveStock(any(), any(), anyString());
    verify(remote, never()).redeemCoupon(any(), any(), any(), any(), anyString(), anyBoolean());
    verify(remote, never()).redeemCoins(any(), anyInt(), any());
    verify(remote, never()).debitWallet(any(), any(), anyString(), any());
    verify(remote, never()).createPayment(any(), any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("buy-now never reads the cart, so a broken cart contract does not affect it")
  void buyNowIgnoresCartContract() {
    cartHolds(null);

    OrderService.Placement p = orders.place(user, "c@example.com", "buy-now-ok", buyNow(shirt, 1));

    assertThat(p.created()).isTrue();
    verify(carts, never()).getCart();
  }

  // --- AC-1, 3, 4, 5, 6, 7: concurrency and duplicate regression cover
  // ----------------------------

  private PlaceOrderRequest fromCartWithExtras() {
    return new PlaceOrderRequest(
        addressId, PaymentMethodRequest.CARD, "SAVE50", null, true, 10, null);
  }

  private void couponAndPartialWalletAvailable() {
    when(remote.validateCoupon(eq("SAVE50"), any(), eq("INR"), anyBoolean()))
        .thenReturn(
            new PromotionFeignClient.Validation(
                "SAVE50", "50 off", new BigDecimal("50.00"), "INR"));
    when(remote.walletBalance(user))
        .thenReturn(new PaymentFeignClient.WalletBalance(new BigDecimal("100.00"), "INR"));
  }

  private record Outcome(OrderService.Placement placement, CodedException error) {}

  /** Runs one placement per key at the same moment and returns the outcomes in key order. */
  private List<Outcome> race(UUID who, List<String> keys, PlaceOrderRequest req) throws Exception {
    var pool = java.util.concurrent.Executors.newFixedThreadPool(keys.size());
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      List<java.util.concurrent.Future<Outcome>> futures = new java.util.ArrayList<>();
      for (String key : keys) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    return new Outcome(orders.place(who, "c@example.com", key, req), null);
                  } catch (CodedException e) {
                    return new Outcome(null, e);
                  }
                }));
      }
      start.countDown();
      List<Outcome> out = new java.util.ArrayList<>();
      for (var f : futures) {
        out.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
      }
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("AC-1: two different-key placements race: one order, one 409, each charge once")
  void differentKeyRaceChargesOnce() throws Exception {
    cartHolds(Instant.parse("2026-11-18T13:00:00Z"));
    couponAndPartialWalletAvailable();

    List<Outcome> outcomes = race(user, List.of("one-a", "one-b"), fromCartWithExtras());

    assertThat(outcomes.stream().filter(o -> o.placement() != null)).hasSize(1);
    Outcome loser = outcomes.stream().filter(o -> o.error() != null).findFirst().orElseThrow();
    assertThat(loser.error().errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    assertThat(orderCount()).isEqualTo(1);
    verify(remote, times(1)).reserveStock(any(), any(), anyString());
    verify(remote, times(1))
        .redeemCoupon(eq("SAVE50"), eq(user), any(), any(), eq("INR"), anyBoolean());
    verify(remote, times(1)).redeemCoins(eq(user), eq(10), any());
    verify(remote, times(1)).debitWallet(eq(user), any(), eq("INR"), any());
  }

  @Test
  @DisplayName("AC-3: the same key and address, repeated, returns the original order")
  void sameKeyRepeatedReturnsOriginal() {
    cartHolds(Instant.parse("2026-11-18T14:00:00Z"));

    OrderService.Placement first = orders.place(user, "c@example.com", "same-key", fromCart());
    OrderService.Placement again = orders.place(user, "c@example.com", "same-key", fromCart());

    assertThat(first.created()).isTrue();
    assertThat(again.created()).isFalse();
    assertThat(again.order().id()).isEqualTo(first.order().id());
    assertThat(orderCount()).isEqualTo(1);
    verify(remote, times(1)).reserveStock(any(), any(), anyString());
  }

  @Test
  @DisplayName("AC-3: the same key and address, raced, gives one order and one created=false")
  void sameKeyRaceReturnsOriginal() throws Exception {
    cartHolds(Instant.parse("2026-11-18T14:30:00Z"));

    List<Outcome> outcomes = race(user, List.of("dup-key", "dup-key"), fromCart());

    assertThat(outcomes).allSatisfy(o -> assertThat(o.placement()).isNotNull());
    assertThat(outcomes.stream().filter(o -> o.placement().created())).hasSize(1);
    assertThat(outcomes.get(0).placement().order().id())
        .isEqualTo(outcomes.get(1).placement().order().id());
    assertThat(orderCount()).isEqualTo(1);
    verify(remote, times(1)).reserveStock(any(), any(), anyString());
  }

  @Test
  @DisplayName("AC-4: after the first order is CANCELLED the same cart can be ordered again")
  void cancelledOrderFreesTheCart() {
    cartHolds(Instant.parse("2026-11-18T15:00:00Z"));
    OrderService.Placement first = orders.place(user, "c@example.com", "c-1", fromCart());
    jdbc.update("update orders.orders set status = 'CANCELLED' where id = ?", first.order().id());

    OrderService.Placement second = orders.place(user, "c@example.com", "c-2", fromCart());

    assertThat(second.created()).isTrue();
    assertThat(second.order().id()).isNotEqualTo(first.order().id());
    assertThat(orderCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("AC-5: the cart cleared and the same items re-added is a new cart, not blocked")
  void reAddedItemsAreANewCart() {
    Instant added = Instant.parse("2026-11-18T16:00:00Z");
    cartHolds(added);
    OrderService.Placement first = orders.place(user, "c@example.com", "r-1", fromCart());
    assertThat(status(first.order().id())).isEqualTo("PENDING_PAYMENT");

    cartHolds(added.plusSeconds(3600));
    OrderService.Placement second = orders.place(user, "c@example.com", "r-2", fromCart());

    assertThat(second.created()).isTrue();
    assertThat(orderCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("C5: a cart order clears through the outbox with the saved order's user and lines")
  void cartOrderClearsDurably() {
    Instant added = Instant.parse("2026-11-18T18:00:00Z");
    cartHolds(added);

    OrderService.Placement p = orders.place(user, "c@example.com", "clear-1", fromCart());

    var lines = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(remote, times(1)).clearCart(eq(user), eq(p.order().id()), lines.capture());
    assertThat(lines.getValue()).hasSize(1);
    var line = (com.acme.ecommerce.cart.api.dto.CartItemResponse) lines.getValue().get(0);
    assertThat(line.productId()).isEqualTo(shirt);
    assertThat(line.quantity()).isEqualTo(2);
    assertThat(line.addedAt()).isEqualTo(added);
    verify(carts, never()).clearCart();
  }

  @Test
  @DisplayName("AC-20: buy-now never clears the cart and enqueues nothing")
  void buyNowNeverClears() {
    orders.place(user, "c@example.com", "bn-clear", buyNow(shirt, 1));

    verify(remote, never()).clearCart(any(), any(), any());
    verify(remote, never()).enqueueClearCart(any(), any(), any());
    verify(remote, never()).attemptClearCart(any(), any(), any());
    verify(carts, never()).clearCart();
    verify(carts, never()).getCart();
  }

  @Test
  @DisplayName("C5: a WALLET order from the cart enqueues inside the paid transaction, then tries")
  void walletCartOrderEnqueuesThenAttempts() {
    cartHolds(Instant.parse("2026-11-18T18:30:00Z"));
    when(remote.walletBalance(user))
        .thenReturn(new PaymentFeignClient.WalletBalance(new BigDecimal("100000.00"), "INR"));

    OrderService.Placement p =
        orders.place(
            user,
            "c@example.com",
            "wallet-clear",
            new PlaceOrderRequest(
                addressId, PaymentMethodRequest.CARD, null, null, true, null, null));

    assertThat(p.order().paymentMethod()).isEqualTo("WALLET");
    var order = org.mockito.Mockito.inOrder(remote);
    order.verify(remote).enqueueClearCart(eq(user), eq(p.order().id()), any());
    order.verify(remote).attemptClearCart(eq(user), eq(p.order().id()), any());
    verify(remote, never()).clearCart(any(), any(), any());
  }

  @Test
  @DisplayName("AC-6: buy-now with two different keys makes two orders and never reads the cart")
  void buyNowTwiceMakesTwoOrders() {
    OrderService.Placement a = orders.place(user, "c@example.com", "bn-1", buyNow(shirt, 2));
    OrderService.Placement b = orders.place(user, "c@example.com", "bn-2", buyNow(shirt, 2));

    assertThat(a.created()).isTrue();
    assertThat(b.created()).isTrue();
    assertThat(b.order().id()).isNotEqualTo(a.order().id());
    assertThat(orderCount()).isEqualTo(2);
    verify(carts, never()).getCart();
  }

  @Test
  @DisplayName(
      "AC-7: two users with identical carts both place; one user's order never blocks another")
  void identicalCartsOfTwoUsers() {
    UUID other = UUID.randomUUID();
    when(remote.createPayment(any(), eq(other), any(), eq("INR"), eq("CARD")))
        .thenReturn(
            new PaymentResult("pay-2", null, "REQUIRES_PAYMENT", "RAZORPAY", null, "rzp_test"));
    cartHolds(Instant.parse("2026-11-18T17:00:00Z"));

    OrderService.Placement a = orders.place(user, "a@example.com", "u-a", fromCart());
    OrderService.Placement b = orders.place(other, "b@example.com", "u-b", fromCart());

    assertThat(a.created()).isTrue();
    assertThat(b.created()).isTrue();
    assertThat(b.order().id()).isNotEqualTo(a.order().id());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from orders.orders where user_id = ?", Integer.class, other))
        .isEqualTo(1);
  }

  // --- AC-8, 9: breakers through the real Feign client stack ------------------------------------

  /** A stub HTTP layer that counts the requests that actually reach "the host". */
  private static final class StubHost implements feign.Client {
    final java.util.concurrent.atomic.AtomicInteger requests =
        new java.util.concurrent.atomic.AtomicInteger();
    volatile int status;

    StubHost(int status) {
      this.status = status;
    }

    @Override
    public feign.Response execute(feign.Request request, feign.Request.Options options) {
      requests.incrementAndGet();
      return feign.Response.builder()
          .status(status)
          .request(request)
          .headers(java.util.Map.of())
          .build();
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private <T> T realClient(
      Class<T> type, String host, DependencyBreakerCapability breakers, feign.Client stub) {
    return feign.Feign.builder()
        .contract(new org.springframework.cloud.openfeign.support.SpringMvcContract())
        .retryer(feign.Retryer.NEVER_RETRY)
        // Without an encoder a POST fails with EncodeException before it reaches the client, so
        // the breaker would never see the call.
        .encoder(
            (object, bodyType, template) -> {
              try {
                template.body(JSON.writeValueAsBytes(object), StandardCharsets.UTF_8);
              } catch (JsonProcessingException e) {
                throw new EncodeException(e.getMessage(), e);
              }
            })
        .client(breakers.enrich(stub))
        .target(type, "http://" + host);
  }

  /** Drives the client with 5xx answers until the host's breaker is open. */
  private void trip(Runnable call, CircuitBreakerRegistry registry, String host) {
    for (int i = 0; i < 10; i++) {
      assertThatThrownBy(call::run).isInstanceOf(feign.FeignException.class);
    }
    assertThat(registry.circuitBreaker("dep-" + host).getState())
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  private void assertFailsFast(Runnable placement, StubHost host) {
    assertFailsFast(placement, host, false);
  }

  /** cancelled: the failure came after the order row was saved, so it must end CANCELLED. */
  private void assertFailsFast(Runnable placement, StubHost host, boolean cancelled) {
    int before = host.requests.get();
    long t0 = System.nanoTime();
    assertThatThrownBy(placement::run)
        .isInstanceOf(CodedException.class)
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
    assertThat(host.requests.get()).as("host calls while the breaker is open").isEqualTo(before);
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - t0))
        .as("no read-timeout wait (the read timeout is 3 s; the margin covers a cold, busy CI box)")
        .isLessThan(java.time.Duration.ofSeconds(2));
    if (cancelled) {
      assertThat(orderCount()).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select status from orders.orders where user_id = ?", String.class, user))
          .isEqualTo("CANCELLED");
    } else {
      assertThat(orderCount()).isZero();
    }
  }

  @Test
  @DisplayName("AC-8: an open product-service breaker gives 503 at once, with no host call")
  void openProductBreaker() {
    var registry = DependencyBreakerCapability.defaultRegistry();
    var host = new StubHost(503);
    ProductBatchFeignClient real =
        realClient(
            ProductBatchFeignClient.class,
            "product-service",
            new DependencyBreakerCapability(registry),
            host);
    when(products.batch(anyString(), anyBoolean()))
        .thenAnswer(inv -> real.batch(inv.getArgument(0), inv.getArgument(1)));
    trip(() -> real.batch(shirt.toString(), true), registry, "product-service");

    assertFailsFast(
        () -> orders.place(user, "c@example.com", "open-product", buyNow(shirt, 1)), host);
  }

  @Test
  @DisplayName("AC-8: an open cart-service breaker gives 503 at once, with no host call")
  void openCartBreaker() {
    var registry = DependencyBreakerCapability.defaultRegistry();
    var host = new StubHost(503);
    CartFeignClient real =
        realClient(
            CartFeignClient.class, "cart-service", new DependencyBreakerCapability(registry), host);
    when(carts.getCart()).thenAnswer(inv -> real.getCart());
    trip(real::getCart, registry, "cart-service");

    assertFailsFast(() -> orders.place(user, "c@example.com", "open-cart", fromCart()), host);
  }

  @Test
  @DisplayName("AC-8: an open user-service breaker gives 503 at once, with no host call")
  void openUserBreaker() {
    var registry = DependencyBreakerCapability.defaultRegistry();
    var host = new StubHost(503);
    UserFeignClient real =
        realClient(
            UserFeignClient.class, "user-service", new DependencyBreakerCapability(registry), host);
    when(users.findAddress(any())).thenAnswer(inv -> real.findAddress(inv.getArgument(0)));
    trip(() -> real.findAddress(addressId), registry, "user-service");

    assertFailsFast(() -> orders.place(user, "c@example.com", "open-user", buyNow(shirt, 1)), host);
  }

  @Test
  @DisplayName("AC-8: an open inventory-service breaker gives 503 at once; the order is CANCELLED")
  void openInventoryBreaker() {
    var registry = DependencyBreakerCapability.defaultRegistry();
    var host = new StubHost(503);
    InventoryFeignClient real =
        realClient(
            InventoryFeignClient.class,
            "inventory-service",
            new DependencyBreakerCapability(registry),
            host);
    when(remote.reserveStock(any(), any(), anyString()))
        .thenAnswer(
            inv -> {
              try {
                return real.reserve(
                        new InventoryFeignClient.ReserveRequest(
                            inv.getArgument(0), List.of(), inv.getArgument(2)))
                    .allocations();
              } catch (feign.FeignException e) {
                throw RemoteCalls.translate("inventory-service", e);
              }
            });
    trip(
        () ->
            real.reserve(
                new InventoryFeignClient.ReserveRequest(UUID.randomUUID(), List.of(), "560001")),
        registry,
        "inventory-service");

    assertFailsFast(
        () -> orders.place(user, "c@example.com", "open-inventory", buyNow(shirt, 1)), host, true);
  }

  @Test
  @DisplayName("AC-8: an open promotion-service breaker gives 503 at once, with no host call")
  void openPromotionBreaker() {
    var registry = DependencyBreakerCapability.defaultRegistry();
    var host = new StubHost(503);
    PromotionFeignClient real =
        realClient(
            PromotionFeignClient.class,
            "promotion-service",
            new DependencyBreakerCapability(registry),
            host);
    when(remote.validateCoupon(eq("SAVE50"), any(), anyString(), anyBoolean()))
        .thenAnswer(
            inv -> {
              try {
                return real.validate(
                    new PromotionFeignClient.ValidateRequest(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), false));
              } catch (feign.FeignException e) {
                throw RemoteCalls.translate("promotion-service", e);
              }
            });
    trip(
        () ->
            real.validate(
                new PromotionFeignClient.ValidateRequest("SAVE50", BigDecimal.TEN, "INR", false)),
        registry,
        "promotion-service");
    PlaceOrderRequest withCoupon =
        new PlaceOrderRequest(
            addressId,
            PaymentMethodRequest.CARD,
            "SAVE50",
            List.of(new OrderLineRequest(shirt, 1, null, null)),
            null,
            null,
            null);

    assertFailsFast(() -> orders.place(user, "c@example.com", "open-promo", withCoupon), host);
  }

  @Test
  @DisplayName("AC-8: an open payment-service breaker gives 503 at once; the order is CANCELLED")
  void openPaymentBreaker() {
    var registry = DependencyBreakerCapability.defaultRegistry();
    var host = new StubHost(503);
    PaymentFeignClient real =
        realClient(
            PaymentFeignClient.class,
            "payment-service",
            new DependencyBreakerCapability(registry),
            host);
    when(remote.createPayment(any(), eq(user), any(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              try {
                return real.create(
                    new PaymentFeignClient.CreatePayment(
                        inv.getArgument(0),
                        inv.getArgument(1),
                        inv.getArgument(2),
                        inv.getArgument(3),
                        inv.getArgument(4)));
              } catch (feign.FeignException e) {
                throw RemoteCalls.translate("payment-service", e);
              }
            });
    trip(
        () ->
            real.create(
                new PaymentFeignClient.CreatePayment(
                    UUID.randomUUID(), user, BigDecimal.TEN, "INR", "CARD")),
        registry,
        "payment-service");

    assertFailsFast(
        () -> orders.place(user, "c@example.com", "open-payment", buyNow(shirt, 1)), host, true);
  }

  @Test
  @DisplayName(
      "AC-9: a 404 from product-service keeps its domain error and never opens the breaker")
  void productNotFoundKeepsDomainError() {
    var registry = DependencyBreakerCapability.defaultRegistry();
    var host = new StubHost(404);
    ProductBatchFeignClient real =
        realClient(
            ProductBatchFeignClient.class,
            "product-service",
            new DependencyBreakerCapability(registry),
            host);
    when(products.batch(anyString(), anyBoolean()))
        .thenAnswer(inv -> real.batch(inv.getArgument(0), inv.getArgument(1)));

    for (int i = 0; i < 25; i++) {
      assertThatThrownBy(
              () ->
                  orders.place(user, "c@example.com", "nf-" + UUID.randomUUID(), buyNow(shirt, 1)))
          .isInstanceOf(CodedException.class)
          .extracting(e -> ((CodedException) e).errorCode())
          .isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE);
    }

    assertThat(host.requests.get()).isEqualTo(25);
    assertThat(registry.circuitBreaker("dep-product-service").getState())
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("AC-9: a 404 from user-service keeps ADDRESS_NOT_FOUND and never opens the breaker")
  void addressNotFoundKeepsDomainError() {
    var registry = DependencyBreakerCapability.defaultRegistry();
    var host = new StubHost(404);
    UserFeignClient real =
        realClient(
            UserFeignClient.class, "user-service", new DependencyBreakerCapability(registry), host);
    when(users.findAddress(any())).thenAnswer(inv -> real.findAddress(inv.getArgument(0)));

    for (int i = 0; i < 25; i++) {
      assertThatThrownBy(
              () ->
                  orders.place(user, "c@example.com", "an-" + UUID.randomUUID(), buyNow(shirt, 1)))
          .isInstanceOf(CodedException.class)
          .extracting(e -> ((CodedException) e).errorCode())
          .isEqualTo(ErrorCode.ADDRESS_NOT_FOUND);
    }

    assertThat(registry.circuitBreaker("dep-user-service").getState())
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }

  // --- AC-10..14: one batched product lookup (PR-B) ------------------------------------------

  private static com.acme.ecommerce.cart.api.dto.CartItemResponse cartLine(
      UUID productId, int qty, UUID variantId, UUID offerId, String name, String price) {
    return new com.acme.ecommerce.cart.api.dto.CartItemResponse(
        productId,
        qty,
        name,
        new BigDecimal(price),
        "INR",
        "http://evil.example/cart.png",
        new BigDecimal(price).multiply(BigDecimal.valueOf(qty)),
        Instant.parse("2026-11-20T10:00:00Z"),
        variantId,
        null,
        offerId,
        null);
  }

  private void cartWith(List<com.acme.ecommerce.cart.api.dto.CartItemResponse> items) {
    when(carts.getCart())
        .thenReturn(
            new com.acme.ecommerce.cart.api.dto.CartResponse(
                items, "INR", items.size(), BigDecimal.ZERO, List.of()));
  }

  private static com.acme.ecommerce.order.client.ProductViewFeignClient.Variant variant(
      UUID id, String price, String salePrice, boolean active) {
    return new com.acme.ecommerce.order.client.ProductViewFeignClient.Variant(
        id,
        "V-" + id.toString().substring(0, 4),
        Map.of("size", "M"),
        new BigDecimal(price),
        salePrice == null ? null : new BigDecimal(salePrice),
        null,
        active);
  }

  private static ProductView productWith(
      UUID id,
      String name,
      String price,
      boolean active,
      List<com.acme.ecommerce.order.client.ProductViewFeignClient.Variant> variants,
      List<com.acme.ecommerce.order.client.ProductViewFeignClient.Offer> offers) {
    return new ProductView(
        id,
        "SKU-" + name,
        name,
        new BigDecimal(price),
        "INR",
        active,
        null,
        "http://cdn.example/" + name + ".png",
        variants,
        offers,
        null);
  }

  private CodedException placeFromCartFails(String key) {
    Throwable t =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> orders.place(user, "c@example.com", key, fromCart()));
    assertThat(t).isInstanceOf(CodedException.class);
    return (CodedException) t;
  }

  @Test
  @DisplayName("AC-10: a 50-line cart makes exactly one product-service request, priced per line")
  void fiftyLineCartMakesOneProductRequest() {
    List<com.acme.ecommerce.cart.api.dto.CartItemResponse> items = new java.util.ArrayList<>();
    Map<UUID, BigDecimal> expectedUnit = new HashMap<>();
    Map<UUID, UUID> expectedVariant = new HashMap<>();
    Map<UUID, UUID> expectedOffer = new HashMap<>();
    Map<UUID, UUID> expectedSeller = new HashMap<>();
    for (int i = 0; i < 50; i++) {
      UUID id = UUID.randomUUID();
      String name = "P" + i;
      BigDecimal base = new BigDecimal(10 + i);
      if (i % 5 == 0) {
        UUID variantId = UUID.randomUUID();
        catalog.put(
            id,
            productWith(
                id,
                name,
                base.toString(),
                true,
                List.of(variant(variantId, base.add(BigDecimal.ONE).toString(), null, true)),
                List.of()));
        items.add(cartLine(id, 1, variantId, null, "client-name", "0.01"));
        expectedUnit.put(id, base.add(BigDecimal.ONE));
        expectedVariant.put(id, variantId);
      } else if (i % 7 == 3) {
        UUID offerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        var offer =
            new com.acme.ecommerce.order.client.ProductViewFeignClient.Offer(
                offerId, sellerId, "Seller", null, base.add(BigDecimal.TWO), null, "INR", 2, 9);
        catalog.put(id, productWith(id, name, base.toString(), true, List.of(), List.of(offer)));
        items.add(cartLine(id, 1, null, offerId, "client-name", "0.01"));
        expectedUnit.put(id, base.add(BigDecimal.TWO));
        expectedOffer.put(id, offerId);
        expectedSeller.put(id, sellerId);
      } else {
        catalog.put(id, productWith(id, name, base.toString(), true, List.of(), List.of()));
        items.add(cartLine(id, 1, null, null, "client-name", "0.01"));
        expectedUnit.put(id, base);
      }
    }
    cartWith(items);

    OrderService.Placement p = orders.place(user, "c@example.com", "fifty", fromCart());

    var csv = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(products, times(1)).batch(csv.capture(), eq(true));
    verifyNoMoreInteractions(products);
    assertThat(csv.getValue().split(",")).hasSize(50).doesNotHaveDuplicates();
    assertThat(p.order().items()).hasSize(50);
    // Sanity: the fixture really has variant lines and offer lines to check.
    assertThat(expectedVariant).isNotEmpty();
    assertThat(expectedOffer).isNotEmpty();
    BigDecimal subtotal = BigDecimal.ZERO;
    for (var line : p.order().items()) {
      assertThat(line.unitPrice())
          .as("price of %s", line.name())
          .isEqualByComparingTo(expectedUnit.get(line.productId()));
      assertThat(line.name()).startsWith("P");
      assertThat(line.variantId()).isEqualTo(expectedVariant.get(line.productId()));
      assertThat(line.offerId()).isEqualTo(expectedOffer.get(line.productId()));
      if (expectedSeller.containsKey(line.productId())) {
        assertThat(line.sellerId()).isEqualTo(expectedSeller.get(line.productId()));
      } else {
        // No offer: the line is sold by the platform seller.
        assertThat(line.sellerId()).isNotNull();
      }
      subtotal = subtotal.add(expectedUnit.get(line.productId()));
    }
    assertThat(p.order().subtotalAmount()).isEqualByComparingTo(subtotal);
    assertThat(p.order().currency()).isEqualTo("INR");
  }

  @Test
  @DisplayName("AC-11: an inactive product keeps PRODUCT_UNAVAILABLE and the message names it")
  void inactiveProductNamedInMessage() {
    UUID retired = UUID.randomUUID();
    catalog.put(retired, productWith(retired, "Old Lamp", "5.00", false, List.of(), List.of()));
    cartWith(List.of(cartLine(retired, 1, null, null, "x", "5.00")));

    CodedException e = placeFromCartFails("a11-inactive");

    assertThat(e.errorCode()).isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE);
    assertThat(e.getMessage()).contains("Old Lamp");
    assertThat(orderCount()).isZero();
  }

  @Test
  @DisplayName("AC-11: an inactive or unknown variant keeps PRODUCT_UNAVAILABLE naming the product")
  void variantUnavailableNamedInMessage() {
    UUID p = UUID.randomUUID();
    UUID inactiveVariant = UUID.randomUUID();
    catalog.put(
        p,
        productWith(
            p,
            "Tee",
            "20.00",
            true,
            List.of(variant(inactiveVariant, "20.00", null, false)),
            List.of()));

    cartWith(List.of(cartLine(p, 1, inactiveVariant, null, "x", "20.00")));
    CodedException inactive = placeFromCartFails("a11-v-inactive");
    cartWith(List.of(cartLine(p, 1, UUID.randomUUID(), null, "x", "20.00")));
    CodedException unknown = placeFromCartFails("a11-v-unknown");

    for (CodedException e : List.of(inactive, unknown)) {
      assertThat(e.errorCode()).isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE);
      assertThat(e.getMessage()).contains("Tee");
    }
    assertThat(orderCount()).isZero();
  }

  @Test
  @DisplayName("AC-11: a product with variants but no variant on the line is VALIDATION_FAILED")
  void missingVariantChoiceIsValidationFailed() {
    UUID p = UUID.randomUUID();
    UUID v = UUID.randomUUID();
    catalog.put(
        p,
        productWith(
            p, "Hoodie", "50.00", true, List.of(variant(v, "50.00", null, true)), List.of()));
    cartWith(List.of(cartLine(p, 1, null, null, "x", "50.00")));

    CodedException e = placeFromCartFails("a11-choose");

    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(e.getMessage()).contains("Hoodie");
    assertThat(orderCount()).isZero();
  }

  @Test
  @DisplayName("AC-13: the same product on two lines sends one id and prices both lines")
  void sameProductOnTwoLinesIsOneLookup() {
    UUID p = UUID.randomUUID();
    UUID v1 = UUID.randomUUID();
    UUID v2 = UUID.randomUUID();
    catalog.put(
        p,
        productWith(
            p,
            "Shoe",
            "100.00",
            true,
            List.of(variant(v1, "120.00", null, true), variant(v2, "150.00", "140.00", true)),
            List.of()));
    cartWith(List.of(cartLine(p, 1, v1, null, "x", "1.00"), cartLine(p, 2, v2, null, "x", "1.00")));

    OrderService.Placement placed = orders.place(user, "c@example.com", "a13", fromCart());

    var csv = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(products, times(1)).batch(csv.capture(), eq(true));
    assertThat(csv.getValue()).isEqualTo(p.toString());
    assertThat(placed.order().items()).hasSize(2);
    assertThat(placed.order().items())
        .filteredOn(i -> v1.equals(i.variantId()))
        .singleElement()
        .satisfies(i -> assertThat(i.lineTotal()).isEqualByComparingTo("120.00"));
    assertThat(placed.order().items())
        .filteredOn(i -> v2.equals(i.variantId()))
        .singleElement()
        .satisfies(i -> assertThat(i.lineTotal()).isEqualByComparingTo("280.00"));
  }

  @Test
  @DisplayName("AC-14: a batch that omits a requested id is PRODUCT_UNAVAILABLE with no order row")
  void batchOmittingAnIdLeavesNoOrder() {
    UUID gone = UUID.randomUUID();
    // shirt is in the catalog, gone is not: the stubbed batch returns only the shirt.
    cartWith(
        List.of(
            cartLine(shirt, 1, null, null, "Shirt", "499.00"),
            cartLine(gone, 1, null, null, "Gone", "5.00")));

    CodedException e = placeFromCartFails("a14");

    assertThat(e.errorCode()).isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE);
    assertThat(orderCount()).isZero();
    verify(remote, never()).reserveStock(any(), any(), anyString());
  }

  @Test
  @DisplayName("AC-12: client-supplied cart price, name and image are ignored; the catalog wins")
  void clientSuppliedCartFieldsAreIgnored() {
    UUID tee = UUID.randomUUID();
    catalog.put(tee, productWith(tee, "Tee", "499.00", true, List.of(), List.of()));
    cartWith(List.of(cartLine(tee, 2, null, null, "HACKED NAME", "0.01")));

    OrderService.Placement placed = orders.place(user, "c@example.com", "a12-ignore", fromCart());

    var line = placed.order().items().get(0);
    assertThat(line.unitPrice()).isEqualByComparingTo("499.00");
    assertThat(line.name()).isEqualTo("Tee");
    assertThat(line.imageUrl()).isEqualTo("http://cdn.example/Tee.png");
    assertThat(placed.order().subtotalAmount()).isEqualByComparingTo("998.00");
  }

  @Autowired(required = false)
  private List<feign.RequestInterceptor> feignInterceptors = List.of();

  @Test
  @DisplayName("AC-12: the batch call carries the request's Authorization bearer, never another")
  void batchCallForwardsTheCallersBearer() {
    var forwarding =
        feignInterceptors.stream()
            .filter(
                i ->
                    i
                        instanceof
                        com.acme.ecommerce.common.web.feign.CorrelationAndAuthForwardingInterceptor)
            .findFirst();
    assertThat(forwarding).as("the common-web interceptor is registered for Feign").isPresent();

    java.util.concurrent.atomic.AtomicReference<feign.Request> seen =
        new java.util.concurrent.atomic.AtomicReference<>();
    feign.Client capture =
        (request, options) -> {
          seen.set(request);
          return feign.Response.builder()
              .status(200)
              .request(request)
              .headers(Map.of("Content-Type", List.of("application/json")))
              .body("[]", StandardCharsets.UTF_8)
              .build();
        };
    ProductBatchFeignClient real =
        feign.Feign.builder()
            .contract(new org.springframework.cloud.openfeign.support.SpringMvcContract())
            .decoder(
                (response, type) -> {
                  try (var in = response.body().asInputStream()) {
                    return JSON.readValue(in, JSON.constructType(type));
                  }
                })
            .requestInterceptor(forwarding.get())
            .client(capture)
            .target(ProductBatchFeignClient.class, "http://product-service");

    var mockRequest = new org.springframework.mock.web.MockHttpServletRequest();
    mockRequest.addHeader("Authorization", "Bearer alice-token");
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        new org.springframework.web.context.request.ServletRequestAttributes(mockRequest));
    try {
      real.batch(shirt.toString(), true);
    } finally {
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    assertThat(seen.get().headers().get("Authorization")).containsExactly("Bearer alice-token");
    assertThat(seen.get().url()).contains("/api/products/batch").contains("detail=true");

    // Sanity for the assertion above: with no inbound request there is nothing to forward, so the
    // header is absent rather than another user's.
    real.batch(shirt.toString(), true);
    assertThat(seen.get().headers()).doesNotContainKey("Authorization");
  }
}
