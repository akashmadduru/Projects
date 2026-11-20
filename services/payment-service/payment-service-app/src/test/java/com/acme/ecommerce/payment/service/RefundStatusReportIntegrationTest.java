package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.payment.api.dto.RefundRequest;
import com.acme.ecommerce.payment.support.AbstractPaymentIntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * O-05a T1: the refund reply carries the status of the refund row for the request key, and a replay
 * is a DB lookup that never reaches the gateway (CTO condition 3).
 */
@TestPropertySource(
    properties = {
      "payment.razorpay.key-id=rzp_test_id",
      "payment.razorpay.key-secret=rzp_secret",
      "payment.razorpay.webhook-secret=whsec_test"
    })
class RefundStatusReportIntegrationTest extends AbstractPaymentIntegrationTest {

  @Autowired private PaymentService service;
  @Autowired private JdbcClient jdbc;
  @MockitoSpyBean private RazorpayGateway razorpay;

  private record Pay(UUID order, UUID user, String rzp) {}

  private Pay payment(String method, String mode, String status) {
    UUID order = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    String rzp = "pay_" + UUID.randomUUID().toString().substring(0, 12);
    jdbc.sql(
            "INSERT INTO payment.payments (order_id, user_id, method, mode, amount, currency, "
                + "status, provider_order_id, provider_payment_id, purpose, reference_id) "
                + "VALUES (:o, :u, :m, :mode, 500.00, 'INR', :s, :po, :pp, 'ORDER', :o)")
        .param("o", order)
        .param("u", user)
        .param("m", method)
        .param("mode", mode)
        .param("s", status)
        .param("po", "order_" + order)
        .param("pp", rzp)
        .update();
    return new Pay(order, user, rzp);
  }

  private static RefundRequest req(String amount, String key, String dest) {
    return new RefundRequest(new BigDecimal(amount), "test", key, dest);
  }

  @Test
  @DisplayName("a Razorpay refund replies refundStatus PENDING")
  void pending() {
    Pay p = payment("CARD", "RAZORPAY", "SUCCEEDED");
    doReturn("rfnd_1").when(razorpay).refund(eq(p.rzp()), anyLong(), anyString(), any());

    var res = service.refund(p.order(), req("500.00", "k-1", "SOURCE"));

    assertThat(res.refundStatus()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("a replay reports the stored status and never calls RazorpayGateway")
  void replayNeverCallsGateway() {
    Pay p = payment("CARD", "RAZORPAY", "SUCCEEDED");
    doReturn("rfnd_2").when(razorpay).refund(eq(p.rzp()), anyLong(), anyString(), any());
    service.refund(p.order(), req("500.00", "k-2", "SOURCE"));
    verify(razorpay, times(1)).refund(eq(p.rzp()), anyLong(), anyString(), any());

    var replay = service.refund(p.order(), req("500.00", "k-2", "SOURCE"));

    assertThat(replay.refundStatus()).isEqualTo("PENDING");
    verify(razorpay, times(1)).refund(eq(p.rzp()), anyLong(), anyString(), any());
    verifyNoMoreInteractions(razorpay);
  }

  @Test
  @DisplayName("after the refund row is FAILED the replay reports FAILED, with no gateway call")
  void replayAfterFailed() {
    Pay p = payment("CARD", "RAZORPAY", "SUCCEEDED");
    doReturn("rfnd_3").when(razorpay).refund(eq(p.rzp()), anyLong(), anyString(), any());
    service.refund(p.order(), req("500.00", "k-3", "SOURCE"));
    jdbc.sql("UPDATE payment.refunds SET status = 'FAILED' WHERE idempotency_key = 'k-3'").update();

    var replay = service.refund(p.order(), req("500.00", "k-3", "SOURCE"));

    assertThat(replay.refundStatus()).isEqualTo("FAILED");
    verify(razorpay, times(1)).refund(eq(p.rzp()), anyLong(), anyString(), any());
  }

  @Test
  @DisplayName("a paid COD refund to WALLET raises refunded_amount and writes one wallet row")
  void codToWallet() {
    Pay p = payment("COD", "SIMULATED", "SUCCEEDED");

    var res = service.refund(p.order(), req("200.00", "return-cod-1", "WALLET"));
    service.refund(p.order(), req("200.00", "return-cod-1", "WALLET"));

    assertThat(res.refundStatus()).isEqualTo("SUCCEEDED");
    assertThat(
            jdbc.sql("SELECT refunded_amount FROM payment.payments WHERE order_id = :o")
                .param("o", p.order())
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("200.00");
    assertThat(
            jdbc.sql("SELECT count(*) FROM payment.wallet_txns WHERE user_id = :u")
                .param("u", p.user())
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("a COD payment still COD_PENDING is rejected with 409")
  void codPending() {
    Pay p = payment("COD", "SIMULATED", "COD_PENDING");

    assertThatThrownBy(() -> service.refund(p.order(), req("200.00", "k-cp", "WALLET")))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
  }

  @Test
  @DisplayName("a missing payment is a 404")
  void missing() {
    assertThatThrownBy(() -> service.refund(UUID.randomUUID(), req("1.00", "k-m", "WALLET")))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND));
  }
}
