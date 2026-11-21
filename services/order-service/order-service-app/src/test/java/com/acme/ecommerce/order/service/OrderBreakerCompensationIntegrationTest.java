package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.OrderLineRequest;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest.PaymentMethodRequest;
import com.acme.ecommerce.order.client.CartFeignClient;
import com.acme.ecommerce.order.client.InventoryFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.ProductViewFeignClient;
import com.acme.ecommerce.order.client.ProductViewFeignClient.ProductView;
import com.acme.ecommerce.order.client.PromotionFeignClient;
import com.acme.ecommerce.order.client.SellerInternalFeignClient;
import com.acme.ecommerce.order.client.UserFeignClient;
import com.acme.ecommerce.order.client.UserInternalFeignClient;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.support.AbstractOrderIntegrationTest;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * AC-9b: a placement that fails on an open breaker after the order row exists still runs the
 * compensation. {@link OrderRemote} and the outbox are real here (OrderPlacementIntegrationTest
 * mocks OrderRemote), so the durable rows can be read back; only the Feign clients are mocked.
 */
class OrderBreakerCompensationIntegrationTest extends AbstractOrderIntegrationTest {

  @MockitoBean private ProductViewFeignClient products;
  @MockitoBean private UserFeignClient users;
  @MockitoBean private CartFeignClient carts;
  @MockitoBean private InventoryFeignClient inventory;
  @MockitoBean private PromotionFeignClient promotions;
  @MockitoBean private PaymentFeignClient payments;
  @MockitoBean private UserInternalFeignClient usersInternal;
  @MockitoBean private SellerInternalFeignClient sellers;
  @MockitoBean private RiskService risks;
  @MockitoBean private OrderEventPublisher events;

  @Autowired private OrderService orders;
  @Autowired private JdbcTemplate jdbc;

  private final UUID user = UUID.randomUUID();
  private final UUID addressId = UUID.randomUUID();
  private final UUID shirt = UUID.randomUUID();

  /** What Feign throws when DependencyBreakerCapability refuses a call (an IOException). */
  private static FeignException openBreaker(String host) {
    Request request =
        Request.create(
            Request.HttpMethod.POST,
            "http://" + host + "/x",
            Map.of(),
            null,
            StandardCharsets.UTF_8,
            null);
    return new RetryableException(
        -1,
        "Circuit open for " + host + ": failing fast",
        Request.HttpMethod.POST,
        new IOException("Circuit open for " + host + ": failing fast"),
        (Long) null,
        request);
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
    when(products.findById(shirt))
        .thenReturn(
            new ProductView(
                shirt,
                "SKU-Shirt",
                "Shirt",
                new BigDecimal("499.00"),
                "INR",
                true,
                null,
                null,
                List.of(),
                List.of(),
                null));
    when(risks.assess(any(), any(), any())).thenReturn(new RiskService.Assessment(0, List.of()));
    when(inventory.reserve(any()))
        .thenReturn(
            new InventoryFeignClient.ReserveResult(
                List.of(
                    new InventoryFeignClient.Allocation(
                        shirt,
                        null,
                        null,
                        UUID.fromString("eeeeeeee-0000-0000-0000-0000000000b1"),
                        "WH-BLR",
                        "560001",
                        2))));
    when(promotions.validate(any()))
        .thenReturn(
            new PromotionFeignClient.Validation(
                "SAVE50", "50 off", new BigDecimal("50.00"), "INR"));
  }

  private PlaceOrderRequest request(String coupon, Integer coins) {
    return new PlaceOrderRequest(
        addressId,
        PaymentMethodRequest.CARD,
        coupon,
        List.of(new OrderLineRequest(shirt, 2, null, null)),
        null,
        coins,
        null);
  }

  private String orderStatus(String key) {
    return jdbc.queryForObject(
        "select status from orders.orders where user_id = ? and idempotency_key = ?",
        String.class,
        user,
        key);
  }

  private List<String> outboxKinds(String key) {
    return jdbc.queryForList(
        "select o.kind from orders.outbox o join orders.orders r on r.id = o.aggregate_id"
            + " where r.user_id = ? and r.idempotency_key = ?",
        String.class,
        user,
        key);
  }

  @Test
  @DisplayName(
      "coupon redeem hits an open breaker after the order is saved: cancelled, stock and coupon released")
  void couponBreakerOpen() {
    doThrow(openBreaker("promotion-service")).when(promotions).redeem(any());

    assertThatThrownBy(
            () -> orders.place(user, "c@example.com", "cb-coupon", request("SAVE50", null)))
        .isInstanceOf(CodedException.class)
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);

    assertThat(orderStatus("cb-coupon")).isEqualTo("CANCELLED");
    assertThat(outboxKinds("cb-coupon")).contains("RESTORE_STOCK", "RELEASE_COUPON");
    assertThat(outboxKinds("cb-coupon")).doesNotContain("RELEASE_COINS");
  }

  @Test
  @DisplayName(
      "coin redeem hits an open breaker after the coupon was taken: stock, coupon and coins released")
  void coinsBreakerOpen() {
    when(promotions.redeem(any())).thenReturn(new PromotionFeignClient.Redemption(BigDecimal.TEN));
    doThrow(openBreaker("promotion-service")).when(promotions).redeemCoins(any());

    assertThatThrownBy(() -> orders.place(user, "c@example.com", "cb-coins", request("SAVE50", 10)))
        .isInstanceOf(CodedException.class)
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);

    assertThat(orderStatus("cb-coins")).isEqualTo("CANCELLED");
    assertThat(outboxKinds("cb-coins"))
        .contains("RESTORE_STOCK", "RELEASE_COUPON", "RELEASE_COINS");
  }

  private PlaceOrderRequest walletRequest() {
    return new PlaceOrderRequest(
        addressId,
        PaymentMethodRequest.CARD,
        null,
        List.of(new OrderLineRequest(shirt, 2, null, null)),
        true,
        null,
        null);
  }

  private List<String> walletRows(String key) {
    return jdbc.queryForList(
        "select w.kind from orders.pending_wallet_credits w join orders.orders r on r.id = w.order_id"
            + " where r.user_id = ? and r.idempotency_key = ?",
        String.class,
        user,
        key);
  }

  @Test
  @DisplayName(
      "wallet debit refused by an open payment breaker: cancelled, stock restored, wallet reversal recorded")
  void walletDebitBreakerOpen() {
    when(payments.walletBalance(user))
        .thenReturn(new PaymentFeignClient.WalletBalance(new BigDecimal("5000.00"), "INR"));
    doThrow(openBreaker("payment-service")).when(payments).walletDebit(any());

    assertThatThrownBy(() -> orders.place(user, "c@example.com", "cb-wallet", walletRequest()))
        .isInstanceOf(CodedException.class)
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);

    assertThat(orderStatus("cb-wallet")).isEqualTo("CANCELLED");
    assertThat(outboxKinds("cb-wallet")).contains("RESTORE_STOCK");
    assertThat(walletRows("cb-wallet")).containsExactly("REVERSE_DEBIT");
  }

  @Test
  @DisplayName(
      "payment create refused after a partial wallet debit: cancelled, wallet credited back")
  void paymentCreateBreakerOpenAfterPartialWalletDebit() {
    when(payments.walletBalance(user))
        .thenReturn(new PaymentFeignClient.WalletBalance(new BigDecimal("300.00"), "INR"));
    when(payments.walletDebit(any()))
        .thenReturn(new PaymentFeignClient.WalletBalance(BigDecimal.ZERO, "INR"));
    doThrow(openBreaker("payment-service")).when(payments).create(any());

    assertThatThrownBy(() -> orders.place(user, "c@example.com", "cb-partial", walletRequest()))
        .isInstanceOf(CodedException.class)
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);

    assertThat(orderStatus("cb-partial")).isEqualTo("CANCELLED");
    assertThat(outboxKinds("cb-partial")).contains("RESTORE_STOCK");
    assertThat(walletRows("cb-partial")).containsExactly("CREDIT");
    assertThat(
            jdbc.queryForObject(
                "select w.amount from orders.pending_wallet_credits w join orders.orders r"
                    + " on r.id = w.order_id where r.user_id = ? and r.idempotency_key = ?",
                BigDecimal.class,
                user,
                "cb-partial"))
        .isEqualByComparingTo("300.00");
  }
}
