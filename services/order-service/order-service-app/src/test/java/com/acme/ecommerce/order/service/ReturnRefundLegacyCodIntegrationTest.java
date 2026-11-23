package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient.CreditLookup;
import com.acme.ecommerce.order.client.PaymentFeignClient.PaymentRow;
import com.acme.ecommerce.order.client.PaymentFeignClient.RefundRequest;
import com.acme.ecommerce.order.client.PaymentFeignClient.RefundResult;
import com.acme.ecommerce.order.client.PaymentFeignClient.WalletCredit;
import com.acme.ecommerce.order.client.PromotionFeignClient;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.Order.ReturnLine;
import com.acme.ecommerce.order.domain.Order.ShipTo;
import com.acme.ecommerce.order.domain.OrderStatus;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.domain.Pricing;
import com.acme.ecommerce.order.domain.ReturnReason;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.repository.OrderRepository;
import com.acme.ecommerce.order.support.AbstractOrderIntegrationTest;
import feign.FeignException;
import feign.Request;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * B-10 PR-B: a COD return stuck on the old 404, with or without the pre-F-09 wallet credit
 * (return-rest-id). The legacy credit must match exactly; nothing is ever paid twice.
 */
class ReturnRefundLegacyCodIntegrationTest extends AbstractOrderIntegrationTest {

  private static final String PAYMENT_ID = "0b1f6a52-5d3e-4a53-9a39-5b6f3c1d2e10";

  @MockitoBean private PaymentFeignClient payments;
  @MockitoBean private PromotionFeignClient promotions;
  @MockitoBean private OrderEventPublisher events;

  @Autowired private ReturnService returns;
  @Autowired private OrderStore store;
  @Autowired private OrderRepository orders;
  @Autowired private JdbcTemplate jdbc;

  private final UUID product = UUID.randomUUID();
  private UUID user;
  private UUID orderId;

  @BeforeEach
  void stubs() {
    reset(payments);
    when(payments.refund(any(), any()))
        .thenAnswer(
            i ->
                new RefundResult(
                    "PARTIALLY_REFUNDED",
                    ((RefundRequest) i.getArgument(1)).amount(),
                    "SUCCEEDED"));
    when(payments.ensureRow(any()))
        .thenReturn(new PaymentRow(PAYMENT_ID, "COD_PENDING", "COD", new BigDecimal("300.00")));
    when(payments.creditLookup(any())).thenReturn(new CreditLookup(false, null, null, null));
  }

  /** A delivered COD order, qty items of 100 each, with a wallet part; payment id optional. */
  private void codOrder(int qty, String wallet, boolean withRow, boolean delivered) {
    Instant now = Instant.now();
    Order o =
        Order.draft(
            UUID.randomUUID(),
            "a@b.c",
            UUID.randomUUID().toString(),
            UUID.randomUUID(),
            new ShipTo("N", "1", "l1", null, "c", "s", "560001", "IN"),
            "INR",
            PaymentMethod.COD,
            now);
    o.addItem(product, null, null, "sku", "P", new BigDecimal("100.00"), qty, null);
    BigDecimal sub = new BigDecimal("100.00").multiply(BigDecimal.valueOf(qty));
    o.applyMoney(0, BigDecimal.ZERO, new BigDecimal(wallet), false, null);
    o.begin(
        new Pricing(
            sub,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            sub,
            BigDecimal.ZERO,
            null,
            null,
            null,
            null,
            BigDecimal.ZERO,
            false),
        null,
        null,
        now);
    if (delivered) {
      o.advance(OrderStatus.DELIVERED, "delivered", null, null, now);
    }
    orders.saveAndFlush(o);
    user = o.getUserId();
    orderId = o.getId();
    if (withRow) {
      jdbc.update("update orders.orders set payment_id = ? where id = ?", PAYMENT_ID, orderId);
    }
  }

  private UUID pickedUpReturn() {
    UUID id =
        store
            .requestReturn(
                user,
                orderId,
                List.of(new ReturnLine(product, null, 1)),
                ReturnReason.DAMAGED,
                null,
                "SOURCE",
                null)
            .ret()
            .id();
    store.decide(id, true, null);
    store.pickedUp(id);
    return id;
  }

  private String status(UUID id) {
    return jdbc.queryForObject(
        "select status from orders.order_returns where id = ?", String.class, id);
  }

  private void legacyCredit(UUID returnId, UUID who, String amount, String currency) {
    when(payments.creditLookup("return-rest-" + returnId))
        .thenReturn(new CreditLookup(true, who, new BigDecimal(amount), currency));
  }

  private void assertNoMoneyMoved() {
    verify(payments, never()).refund(any(), any());
    verify(payments, never()).walletCredit(any());
  }

  // ---- C6: the legacy credit must match exactly ---------------------------------------------

  @Test
  @DisplayName("a legacy credit for a different user is a 409 with no credit and no refund")
  void differentUser() {
    codOrder(3, "0", true, true);
    UUID id = pickedUpReturn();
    legacyCredit(id, UUID.randomUUID(), "100.00", "INR");

    assertThatThrownBy(() -> returns.refund(id))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

    assertNoMoneyMoved();
    assertThat(status(id)).isEqualTo("PICKED_UP");
  }

  @Test
  @DisplayName("a legacy credit of a different amount is a 409 with no credit and no refund")
  void differentAmount() {
    codOrder(3, "0", true, true);
    UUID id = pickedUpReturn();
    legacyCredit(id, user, "99.99", "INR");

    assertThatThrownBy(() -> returns.refund(id)).isInstanceOf(CodedException.class);

    assertNoMoneyMoved();
    assertThat(status(id)).isEqualTo("PICKED_UP");
  }

  @Test
  @DisplayName("a legacy credit in a different currency is a 409 with no credit and no refund")
  void differentCurrency() {
    codOrder(3, "0", true, true);
    UUID id = pickedUpReturn();
    legacyCredit(id, user, "100.00", "USD");

    assertThatThrownBy(() -> returns.refund(id)).isInstanceOf(CodedException.class);

    assertNoMoneyMoved();
    assertThat(status(id)).isEqualTo("PICKED_UP");
  }

  @Test
  @DisplayName(
      "an exact legacy credit (amount by compareTo): no refund call, REFUNDED, retry quiet")
  void exactMatch() {
    codOrder(3, "0", true, true);
    UUID id = pickedUpReturn();
    legacyCredit(id, user, "100", "INR"); // scale differs on purpose

    returns.refund(id);

    assertThat(status(id)).isEqualTo("REFUNDED");
    assertNoMoneyMoved();
    verify(payments, never()).ensureRow(any());
    assertThat(
            jdbc.queryForObject(
                "select rest_leg_state from orders.order_returns where id = ?", String.class, id))
        .isEqualTo("SUCCEEDED");
    returns.refund(id);
    assertNoMoneyMoved();
  }

  // ---- the stuck return is completed ---------------------------------------------------------

  @Test
  @DisplayName("positive control: no legacy credit, rowless COD return -> ensure, refund once")
  void rowlessIsEnsuredThenRefundedOnce() {
    codOrder(3, "0", false, true);
    UUID id = pickedUpReturn();

    returns.refund(id);
    returns.refund(id);

    assertThat(status(id)).isEqualTo("REFUNDED");
    verify(payments, times(1)).ensureRow(any());
    ArgumentCaptor<RefundRequest> sent = ArgumentCaptor.forClass(RefundRequest.class);
    verify(payments, times(1)).refund(any(), sent.capture());
    assertThat(sent.getValue().amount()).isEqualByComparingTo("100.00");
    assertThat(sent.getValue().destination()).isEqualTo("WALLET");
    assertThat(sent.getValue().idempotencyKey()).isEqualTo("return-" + id);
    assertThat(
            jdbc.queryForObject(
                "select payment_id from orders.orders where id = ?", String.class, orderId))
        .isEqualTo(PAYMENT_ID);
    // collected on delivery: the row is marked collected before the refund
    verify(payments, times(1)).codCollected(orderId);
  }

  @Test
  @DisplayName("lookup 404 (old payment-service): a row is there -> refund proceeds")
  void lookup404WithRow() {
    codOrder(3, "0", true, true);
    UUID id = pickedUpReturn();
    when(payments.creditLookup(any())).thenThrow(notFound());

    returns.refund(id);

    assertThat(status(id)).isEqualTo("REFUNDED");
    verify(payments, times(1)).refund(any(), any());
  }

  @Test
  @DisplayName("lookup 404 and no row: 503, PICKED_UP, no ensure, no money")
  void lookup404Rowless() {
    codOrder(3, "0", false, true);
    UUID id = pickedUpReturn();
    when(payments.creditLookup(any())).thenThrow(notFound());

    assertThatThrownBy(() -> returns.refund(id))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));

    assertNoMoneyMoved();
    verify(payments, never()).ensureRow(any());
    assertThat(status(id)).isEqualTo("PICKED_UP");
  }

  @Test
  @DisplayName("rowless and payment-service down on the ensure: 503, PICKED_UP, no money")
  void ensureDown() {
    codOrder(3, "0", false, true);
    UUID id = pickedUpReturn();
    when(payments.ensureRow(any())).thenThrow(unavailable());

    assertThatThrownBy(() -> returns.refund(id))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));

    assertNoMoneyMoved();
    assertThat(status(id)).isEqualTo("PICKED_UP");
  }

  @Test
  @DisplayName("COD_PENDING (undelivered) return: route NONE, no lookup, no ensure, no money")
  void codPendingMovesNothing() {
    codOrder(3, "0", false, false);
    UUID id = jdbcPickedUpUndelivered();

    returns.refund(id);

    assertNoMoneyMoved();
    verify(payments, never()).ensureRow(any());
    verify(payments, never()).creditLookup(any());
  }

  /** requestReturn needs a delivered order; flip to COD_PENDING after the return is picked up. */
  private UUID jdbcPickedUpUndelivered() {
    jdbc.update(
        "update orders.orders set status = 'DELIVERED', delivered_at = now() where id = ?",
        orderId);
    UUID id = pickedUpReturn();
    jdbc.update("update orders.orders set payment_status = 'COD_PENDING' where id = ?", orderId);
    return id;
  }

  // ---- AC-12: the cap ------------------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-12: 3 x 100 COD with wallet 100, three returns: credits sum 100, refunds sum 200")
  void capHoldsAcrossReturns() {
    codOrder(3, "100", true, true);
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ids.add(pickedUpReturn());
    }

    for (UUID id : ids) {
      returns.refund(id);
      returns.refund(id); // a retry pays nothing
    }

    ArgumentCaptor<WalletCredit> credits = ArgumentCaptor.forClass(WalletCredit.class);
    verify(payments, times(3)).walletCredit(credits.capture());
    ArgumentCaptor<RefundRequest> refunds = ArgumentCaptor.forClass(RefundRequest.class);
    verify(payments, times(3)).refund(any(), refunds.capture());
    Map<String, BigDecimal> walletByKey = new HashMap<>();
    credits.getAllValues().forEach(c -> walletByKey.put(c.idempotencyKey(), c.amount()));
    BigDecimal wallet = walletByKey.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal rest =
        refunds.getAllValues().stream()
            .map(RefundRequest::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(wallet).isEqualByComparingTo("100.00");
    assertThat(rest).isEqualByComparingTo("200.00");
    ids.forEach(id -> assertThat(status(id)).isEqualTo("REFUNDED"));
  }

  private static FeignException notFound() {
    return new FeignException.NotFound("nf", request(), null, null);
  }

  private static FeignException unavailable() {
    return new FeignException.ServiceUnavailable("down", request(), null, null);
  }

  private static Request request() {
    return Request.create(Request.HttpMethod.GET, "http://payment", Map.of(), null, null, null);
  }
}
