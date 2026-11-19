package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.payment.api.dto.RefundRequest;
import com.acme.ecommerce.payment.support.AbstractPaymentIntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** AC-12: COD and wallet-destination refunds stay immediately final (SUCCEEDED), as before. */
class RefundNonRazorpayRegressionTest extends AbstractPaymentIntegrationTest {

  @Autowired private PaymentService service;
  @Autowired private JdbcClient jdbc;
  @Autowired private WalletService wallet;
  @MockitoSpyBean private StripeGateway stripe;

  private UUID payment(String method, String mode) {
    UUID order = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO payment.payments (order_id, user_id, method, mode, amount, currency, "
                + "status, provider_order_id, provider_payment_id, purpose, reference_id) "
                + "VALUES (:o, :u, :m, :mode, 300.00, 'INR', 'SUCCEEDED', 'order_x', 'pay_x', "
                + "'ORDER', :o)")
        .param("o", order)
        .param("u", UUID.randomUUID())
        .param("m", method)
        .param("mode", mode)
        .update();
    return order;
  }

  private String refundStatus(UUID order, String key) {
    return jdbc.sql(
            "SELECT r.status FROM payment.refunds r JOIN payment.payments p ON p.id = r.payment_id "
                + "WHERE p.order_id = :o AND r.idempotency_key = :k")
        .param("o", order)
        .param("k", key)
        .query(String.class)
        .single();
  }

  @Test
  @DisplayName("a COD refund is SUCCEEDED and the payment is REFUNDED immediately")
  void codRefund() {
    UUID order = payment("COD", "SIMULATED");

    var res = service.refund(order, new RefundRequest(null, "r", "k-cod", "SOURCE"));

    assertThat(res.status()).isEqualTo("REFUNDED");
    assertThat(res.refundedAmount()).isEqualByComparingTo("300");
    assertThat(refundStatus(order, "k-cod")).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("a wallet-destination refund on a Razorpay payment is SUCCEEDED and credits at once")
  void walletRefund() {
    UUID order = payment("CARD", "RAZORPAY");
    UUID user =
        jdbc.sql("SELECT user_id FROM payment.payments WHERE order_id = :o")
            .param("o", order)
            .query(UUID.class)
            .single();

    var res =
        service.refund(order, new RefundRequest(new BigDecimal("100.00"), "r", "k-wal", "WALLET"));

    assertThat(res.status()).isEqualTo("PARTIALLY_REFUNDED");
    assertThat(refundStatus(order, "k-wal")).isEqualTo("SUCCEEDED");
    assertThat(wallet.balance(user).balance()).isEqualByComparingTo("100.00");
  }

  @Test
  @DisplayName("a Stripe-mode payment without a Stripe intent refunds as SUCCEEDED, as before")
  void stripeRefund() {
    UUID order = payment("CARD", "STRIPE");

    var res = service.refund(order, new RefundRequest(null, "r", "k-str", "SOURCE"));

    assertThat(res.status()).isEqualTo("REFUNDED");
    assertThat(refundStatus(order, "k-str")).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName(
      "a real Stripe refund (intent set, provider called) is SUCCEEDED with the same reply")
  void stripeRefundWithIntent() {
    UUID order = payment("CARD", "STRIPE");
    jdbc.sql("UPDATE payment.payments SET stripe_payment_intent_id = 'pi_1' WHERE order_id = :o")
        .param("o", order)
        .update();
    doReturn("re_1").when(stripe).refund(eq("pi_1"), anyLong(), anyString());

    var res =
        service.refund(order, new RefundRequest(new BigDecimal("100.00"), "r", "k-str2", "SOURCE"));

    assertThat(res.status()).isEqualTo("PARTIALLY_REFUNDED");
    assertThat(res.refundedAmount()).isEqualByComparingTo("100.00");
    verify(stripe).refund(eq("pi_1"), eq(10000L), eq("k-str2"));
    assertThat(refundStatus(order, "k-str2")).isEqualTo("SUCCEEDED");
    assertThat(
            jdbc.sql(
                    "SELECT stripe_refund_id FROM payment.refunds WHERE idempotency_key = 'k-str2'")
                .query(String.class)
                .single())
        .isEqualTo("re_1");
  }
}
