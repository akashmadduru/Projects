package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.payment.api.dto.RefundRequest;
import com.acme.ecommerce.payment.repository.AdminPaymentQueries;
import com.acme.ecommerce.payment.support.AbstractPaymentIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Razorpay refund lifecycle on a real database: PENDING at acceptance, settled by signed webhooks,
 * never overwritten, parked when the event beats the refund row (CTO condition 3). Razorpay HTTP is
 * stubbed through the gateway seam; the signature check is the real one.
 */
@TestPropertySource(
    properties = {
      "payment.razorpay.key-id=rzp_test_id",
      "payment.razorpay.key-secret=rzp_secret",
      "payment.razorpay.webhook-secret=whsec_test"
    })
class RazorpayRefundStatesIntegrationTest extends AbstractPaymentIntegrationTest {

  @Autowired private PaymentService service;
  @Autowired private JdbcClient jdbc;
  @Autowired private WalletService wallet;
  @Autowired private AdminPaymentQueries admin;
  @MockitoSpyBean private RazorpayGateway razorpay;

  private ListAppender<ILoggingEvent> logs;
  private UUID orderId;
  private UUID userId;
  private UUID paymentId;
  private String rzpPayment;

  @BeforeEach
  void setUp() {
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(PaymentService.class)).addAppender(logs);
    orderId = UUID.randomUUID();
    userId = UUID.randomUUID();
    rzpPayment = "pay_" + UUID.randomUUID().toString().substring(0, 12);
    paymentId =
        jdbc.sql(
                "INSERT INTO payment.payments (order_id, user_id, method, mode, amount, currency, "
                    + "status, provider_order_id, provider_payment_id, purpose, reference_id) "
                    + "VALUES (:o, :u, 'CARD', 'RAZORPAY', 500.00, 'INR', 'SUCCEEDED', :po, :pp, "
                    + "'ORDER', :o) RETURNING id")
            .param("o", orderId)
            .param("u", userId)
            .param("po", "order_" + orderId)
            .param("pp", rzpPayment)
            .query(UUID.class)
            .single();
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(PaymentService.class)).detachAppender(logs);
  }

  // ---- helpers ----------------------------------------------------------------------------

  private void providerAccepts(String refundId) {
    doReturn(refundId).when(razorpay).refund(eq(rzpPayment), anyLong(), anyString(), any());
  }

  private void refund(String amount, String key) {
    service.refund(orderId, new RefundRequest(new BigDecimal(amount), "test", key, "SOURCE"));
  }

  private static String refundBody(
      String event, String refundId, String paymentId, long minor, String currency) {
    return "{\"event\":\""
        + event
        + "\",\"payload\":{\"refund\":{\"entity\":{\"id\":\""
        + refundId
        + "\",\"payment_id\":\""
        + paymentId
        + "\",\"amount\":"
        + minor
        + ",\"currency\":\""
        + currency
        + "\",\"status\":\"x\"}}}}";
  }

  private void send(String body, String eventId) {
    service.handleRazorpayWebhook(body, RazorpayGateway.hmacHex("whsec_test", body), eventId);
  }

  private String refundStatus(String refundId) {
    return jdbc.sql("SELECT status FROM payment.refunds WHERE provider_refund_id = :r")
        .param("r", refundId)
        .query(String.class)
        .single();
  }

  private String paymentState() {
    return jdbc.sql(
            "SELECT status || ' ' || refunded_amount::numeric(19,2) FROM payment.payments "
                + "WHERE id = :i")
        .param("i", paymentId)
        .query(String.class)
        .single();
  }

  private long count(Level level, String fragment) {
    return logs.list.stream()
        .filter(e -> e.getLevel() == level && e.getFormattedMessage().contains(fragment))
        .count();
  }

  // ---- AC-1 -------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-1: an accepted refund is PENDING, reserves the balance, and a second refund over the rest is 422")
  void acceptedRefundIsPending() {
    providerAccepts("rfnd_a1");

    refund("200.00", "k-a1");

    assertThat(refundStatus("rfnd_a1")).isEqualTo("PENDING");
    assertThat(paymentState()).isEqualTo("PARTIALLY_REFUNDED 200.00");
    assertThatThrownBy(() -> refund("400.00", "k-a1b"))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DOMAIN_RULE_VIOLATION));
    assertThat(admin.refunds(paymentId))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.status()).isEqualTo("PENDING");
              assertThat(r.providerRefundId()).isEqualTo("rfnd_a1");
            });
  }

  @Test
  @DisplayName(
      "AC-1: two refunds can be pending at once, and replaying a key makes no provider call")
  void twoPendingAndReplay() {
    providerAccepts("rfnd_a2");
    refund("100.00", "k-a2");
    providerAccepts("rfnd_a3");
    refund("150.00", "k-a3");
    refund("100.00", "k-a2");

    assertThat(refundStatus("rfnd_a2")).isEqualTo("PENDING");
    assertThat(refundStatus("rfnd_a3")).isEqualTo("PENDING");
    assertThat(paymentState()).isEqualTo("PARTIALLY_REFUNDED 250.00");
    verify(razorpay, times(2)).refund(eq(rzpPayment), anyLong(), anyString(), any());
  }

  // ---- AC-2 / AC-3 / AC-4 -----------------------------------------------------------------

  @Test
  @DisplayName("AC-2: refund.processed settles SUCCEEDED and leaves the payment unchanged")
  void processed() {
    providerAccepts("rfnd_p1");
    refund("500.00", "k-p1");

    send(refundBody("refund.processed", "rfnd_p1", rzpPayment, 50000, "INR"), "evt-p1");

    assertThat(refundStatus("rfnd_p1")).isEqualTo("SUCCEEDED");
    assertThat(paymentState()).isEqualTo("REFUNDED 500.00");
    assertThat(count(Level.ERROR, "REFUND FAILED")).isZero();
  }

  @Test
  @DisplayName("AC-3: refund.failed releases the amount, restores status, no wallet credit, alerts")
  void failed() {
    providerAccepts("rfnd_f1");
    refund("500.00", "k-f1");

    send(refundBody("refund.failed", "rfnd_f1", rzpPayment, 50000, "INR"), "evt-f1");

    assertThat(refundStatus("rfnd_f1")).isEqualTo("FAILED");
    assertThat(paymentState()).isEqualTo("SUCCEEDED 0.00");
    assertThat(wallet.balance(userId).balance()).isEqualByComparingTo("0");
    assertThat(logs.list)
        .filteredOn(e -> e.getFormattedMessage().contains("REFUND FAILED"))
        .singleElement()
        .satisfies(
            e ->
                assertThat(e.getFormattedMessage()).contains(orderId.toString(), "rfnd_f1", "500"));
  }

  @Test
  @DisplayName(
      "AC-4: after FAILED a new key can refund again; the old key returns the stored outcome")
  void retryAfterFailed() {
    providerAccepts("rfnd_r1");
    refund("500.00", "k-r1");
    send(refundBody("refund.failed", "rfnd_r1", rzpPayment, 50000, "INR"), "evt-r1");

    providerAccepts("rfnd_r2");
    refund("500.00", "k-r2");
    refund("500.00", "k-r1");

    assertThat(refundStatus("rfnd_r1")).isEqualTo("FAILED");
    assertThat(refundStatus("rfnd_r2")).isEqualTo("PENDING");
    assertThat(paymentState()).isEqualTo("REFUNDED 500.00");
    verify(razorpay, times(2)).refund(eq(rzpPayment), anyLong(), anyString(), any());
  }

  @Test
  @DisplayName("two pending refunds: one failing releases only its own amount")
  void onlyOwnAmountReleased() {
    providerAccepts("rfnd_t1");
    refund("100.00", "k-t1");
    providerAccepts("rfnd_t2");
    refund("150.00", "k-t2");

    send(refundBody("refund.failed", "rfnd_t2", rzpPayment, 15000, "INR"), "evt-t2");

    assertThat(paymentState()).isEqualTo("PARTIALLY_REFUNDED 100.00");
    assertThat(refundStatus("rfnd_t1")).isEqualTo("PENDING");
  }

  // ---- AC-5 / AC-6 ------------------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-5: duplicate delivery (same event id, or same refund and state) releases and alerts once")
  void duplicates() {
    providerAccepts("rfnd_d1");
    refund("500.00", "k-d1");
    String body = refundBody("refund.failed", "rfnd_d1", rzpPayment, 50000, "INR");

    send(body, "evt-d1");
    send(body, "evt-d1");
    send(body, "evt-d1-other");
    providerAccepts("rfnd_d2");
    refund("200.00", "k-d2");
    send(body, "evt-d1-third");

    assertThat(count(Level.ERROR, "REFUND FAILED")).isEqualTo(1);
    assertThat(paymentState()).isEqualTo("PARTIALLY_REFUNDED 200.00");
  }

  @Test
  @DisplayName("AC-5: concurrent deliveries of one event release once")
  void concurrentDuplicates() throws Exception {
    providerAccepts("rfnd_c1");
    refund("500.00", "k-c1");
    String body = refundBody("refund.failed", "rfnd_c1", rzpPayment, 50000, "INR");
    int n = 6;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> fs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      fs.add(
          pool.submit(
              () -> {
                go.await();
                send(body, "evt-c1");
                return null;
              }));
    }
    go.countDown();
    for (Future<?> f : fs) {
      f.get();
    }
    pool.shutdown();

    assertThat(count(Level.ERROR, "REFUND FAILED")).isEqualTo(1);
    assertThat(paymentState()).isEqualTo("SUCCEEDED 0.00");
  }

  @Test
  @DisplayName(
      "AC-6: failed then processed, and processed then failed, never overwrite (warn, 200)")
  void terminalNeverOverwritten() {
    providerAccepts("rfnd_o1");
    refund("100.00", "k-o1");
    providerAccepts("rfnd_o2");
    refund("150.00", "k-o2");

    send(refundBody("refund.failed", "rfnd_o1", rzpPayment, 10000, "INR"), "evt-o1a");
    send(refundBody("refund.processed", "rfnd_o1", rzpPayment, 10000, "INR"), "evt-o1b");
    send(refundBody("refund.processed", "rfnd_o2", rzpPayment, 15000, "INR"), "evt-o2a");
    send(refundBody("refund.failed", "rfnd_o2", rzpPayment, 15000, "INR"), "evt-o2b");

    assertThat(refundStatus("rfnd_o1")).isEqualTo("FAILED");
    assertThat(refundStatus("rfnd_o2")).isEqualTo("SUCCEEDED");
    assertThat(paymentState()).isEqualTo("PARTIALLY_REFUNDED 150.00");
    assertThat(count(Level.WARN, "conflicting")).isEqualTo(2);
    assertThat(count(Level.ERROR, "REFUND FAILED")).isEqualTo(1);
  }

  // ---- AC-7 -------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-7: unknown payment, payment/amount/currency mismatch and missing entity change nothing")
  void mismatchesAreIgnored() {
    providerAccepts("rfnd_m1");
    refund("500.00", "k-m1");
    String before = paymentState();

    send(refundBody("refund.failed", "rfnd_m1", "pay_unknown", 50000, "INR"), "evt-m1");
    send(refundBody("refund.failed", "rfnd_m1", rzpPayment, 49900, "INR"), "evt-m2");
    send(refundBody("refund.failed", "rfnd_m1", rzpPayment, 50000, "USD"), "evt-m3");
    send("{\"event\":\"refund.failed\",\"payload\":{}}", "evt-m4");
    send(refundBody("refund.created", "rfnd_m1", rzpPayment, 50000, "INR"), "evt-m5");
    send(refundBody("refund.speed_changed", "rfnd_m1", rzpPayment, 50000, "INR"), "evt-m6");

    assertThat(refundStatus("rfnd_m1")).isEqualTo("PENDING");
    assertThat(paymentState()).isEqualTo(before);
    assertThat(count(Level.ERROR, "REFUND FAILED")).isZero();
  }

  @Test
  @DisplayName("AC-7: a refund id owned by another payment is not applied through this payment")
  void refundOfAnotherPayment() {
    providerAccepts("rfnd_x1");
    refund("500.00", "k-x1");
    String other = "pay_other_" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.sql(
            "INSERT INTO payment.payments (order_id, user_id, method, mode, amount, currency, "
                + "status, provider_payment_id, purpose, reference_id) VALUES (:o, :u, 'CARD', "
                + "'RAZORPAY', 500.00, 'INR', 'SUCCEEDED', :pp, 'ORDER', :o)")
        .param("o", UUID.randomUUID())
        .param("u", UUID.randomUUID())
        .param("pp", other)
        .update();

    send(refundBody("refund.failed", "rfnd_x1", other, 50000, "INR"), "evt-x1");

    assertThat(refundStatus("rfnd_x1")).isEqualTo("PENDING");
    assertThat(paymentState()).isEqualTo("REFUNDED 500.00");
  }

  // ---- AC-8 -------------------------------------------------------------------------------

  @Test
  @DisplayName("AC-8: a bad or missing signature is 400 VALIDATION_FAILED and changes nothing")
  void badSignature() {
    providerAccepts("rfnd_s1");
    refund("500.00", "k-s1");
    String body = refundBody("refund.failed", "rfnd_s1", rzpPayment, 50000, "INR");

    for (String sig : new String[] {"deadbeef", null}) {
      assertThatThrownBy(() -> service.handleRazorpayWebhook(body, sig, "evt-s1"))
          .isInstanceOfSatisfying(
              CodedException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
    assertThat(refundStatus("rfnd_s1")).isEqualTo("PENDING");
    assertThat(paymentState()).isEqualTo("REFUNDED 500.00");
    assertThat(
            jdbc.sql("SELECT count(*) FROM payment.processed_events WHERE event_id = 'rzp:evt-s1'")
                .query(Long.class)
                .single())
        .isZero();
  }

  // ---- CTO 3: race, event before the refund row ---------------------------------------------

  @Test
  @DisplayName("CTO 3: an event that beats the refund row is parked, then applied by refund()")
  void parkedThenApplied() {
    send(refundBody("refund.failed", "rfnd_race", rzpPayment, 50000, "INR"), "evt-race");
    assertThat(parkedCount("rfnd_race")).isEqualTo(1);
    assertThat(paymentState()).isEqualTo("SUCCEEDED 0.00");

    providerAccepts("rfnd_race");
    refund("500.00", "k-race");

    assertThat(refundStatus("rfnd_race")).isEqualTo("FAILED");
    assertThat(paymentState()).isEqualTo("SUCCEEDED 0.00");
    assertThat(parkedCount("rfnd_race")).isZero();
    assertThat(count(Level.ERROR, "REFUND FAILED")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "CTO 3: a parked processed event settles the new refund without touching the payment")
  void parkedProcessedApplied() {
    send(refundBody("refund.processed", "rfnd_race2", rzpPayment, 20000, "INR"), "evt-race2");

    providerAccepts("rfnd_race2");
    refund("200.00", "k-race2");

    assertThat(refundStatus("rfnd_race2")).isEqualTo("SUCCEEDED");
    assertThat(paymentState()).isEqualTo("PARTIALLY_REFUNDED 200.00");
  }

  @Test
  @DisplayName("CTO 3: a parked event that does not match the new refund is dropped, not applied")
  void parkedMismatchDropped() {
    send(refundBody("refund.failed", "rfnd_race3", rzpPayment, 1, "INR"), "evt-race3");

    providerAccepts("rfnd_race3");
    refund("200.00", "k-race3");

    assertThat(refundStatus("rfnd_race3")).isEqualTo("PENDING");
    assertThat(paymentState()).isEqualTo("PARTIALLY_REFUNDED 200.00");
    assertThat(parkedCount("rfnd_race3")).isZero();
  }

  private long parkedCount(String refundId) {
    return jdbc.sql(
            "SELECT count(*) FROM payment.razorpay_refund_parked WHERE provider_refund_id = :r")
        .param("r", refundId)
        .query(Long.class)
        .single();
  }

  @Test
  @DisplayName("the signature gate is first: nothing is read for a bad signature")
  void gateIsFirst() {
    assertThatThrownBy(
            () ->
                service.handleRazorpayWebhook(
                    refundBody("refund.failed", "r", rzpPayment, 1, "INR"), "bad", "e"))
        .isInstanceOf(CodedException.class);
    verify(razorpay, never()).refund(anyString(), anyLong(), anyString(), any());
  }
}
