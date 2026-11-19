package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.ecommerce.payment.support.AbstractPaymentIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * AC-10: capture-mode watch and stuck-refund alert.
 *
 * <p>Source (Q1, CTO condition 1): https://razorpay.com/docs/webhooks/payloads/payments/ . The
 * event is {@code payment.authorized}; the payment is at {@code payload.payment.entity} with {@code
 * order_id} and a {@code captured} boolean. Fact 4: the payment "can be in the captured state when
 * the payment.authorized webhook is fired" and "the webhook sequence is not fixed". So the event
 * alone never alerts; only {@link RazorpayWatch} does, for a payment still unpaid after the grace
 * period (here backdated by SQL), and never when the payment was captured first, captured with the
 * event, or captured within the grace period.
 */
@TestPropertySource(
    properties = {
      "payment.razorpay.key-id=rzp_test_id",
      "payment.razorpay.key-secret=rzp_secret",
      "payment.razorpay.webhook-secret=whsec_test"
    })
class RazorpayCaptureWatchIntegrationTest extends AbstractPaymentIntegrationTest {

  @Autowired private PaymentService service;
  @Autowired private RazorpayWatch watch;
  @Autowired private JdbcClient jdbc;

  private ListAppender<ILoggingEvent> logs;
  private UUID orderId;
  private String rzpOrder;

  @BeforeEach
  void setUp() {
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(RazorpayWatch.class)).addAppender(logs);
    orderId = UUID.randomUUID();
    rzpOrder = "order_" + orderId;
    insertPayment(orderId, rzpOrder, "RAZORPAY", "REQUIRES_PAYMENT");
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(RazorpayWatch.class)).detachAppender(logs);
  }

  private void insertPayment(UUID order, String providerOrder, String mode, String status) {
    jdbc.sql(
            "INSERT INTO payment.payments (order_id, user_id, method, mode, amount, currency, "
                + "status, provider_order_id, purpose, reference_id) "
                + "VALUES (:o, :u, 'CARD', :m, 500.00, 'INR', :s, :po, 'ORDER', :o)")
        .param("o", order)
        .param("u", UUID.randomUUID())
        .param("m", mode)
        .param("s", status)
        .param("po", providerOrder)
        .update();
  }

  private void webhook(String event, String order, boolean captured) {
    String body =
        "{\"event\":\""
            + event
            + "\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_w\",\"order_id\":\""
            + order
            + "\",\"amount\":50000,\"currency\":\"INR\",\"status\":\""
            + (captured ? "captured" : "authorized")
            + "\",\"captured\":"
            + captured
            + "}}}}";
    service.handleRazorpayWebhook(
        body, RazorpayGateway.hmacHex("whsec_test", body), event + "-" + UUID.randomUUID());
  }

  private void age(UUID order, int minutes) {
    jdbc.sql(
            "UPDATE payment.payments SET authorized_at = now() - make_interval(mins => :m) "
                + "WHERE order_id = :o AND authorized_at IS NOT NULL")
        .param("m", minutes)
        .param("o", order)
        .update();
  }

  private boolean authorizedStamped(UUID order) {
    return jdbc.sql("SELECT authorized_at IS NOT NULL FROM payment.payments WHERE order_id = :o")
        .param("o", order)
        .query(Boolean.class)
        .single();
  }

  private String status(UUID order) {
    return jdbc.sql("SELECT status FROM payment.payments WHERE order_id = :o")
        .param("o", order)
        .query(String.class)
        .single();
  }

  private long alerts(String fragment) {
    return logs.list.stream()
        .filter(
            e ->
                e.getLevel() == Level.ERROR
                    && e.getFormattedMessage().contains(fragment)
                    && e.getFormattedMessage().contains(orderId.toString()))
        .count();
  }

  @Test
  @DisplayName(
      "authorized and still unpaid after the grace period: two ticks, one alert, no status change")
  void alertsOnce() {
    webhook("payment.authorized", rzpOrder, false);
    age(orderId, 60);

    watch.runOnce();
    watch.runOnce();

    assertThat(alerts("RAZORPAY NOT CAPTURED")).isEqualTo(1);
    assertThat(status(orderId)).isEqualTo("REQUIRES_PAYMENT");
  }

  @Test
  @DisplayName("the event alone (inside the grace period) never alerts")
  void eventAloneDoesNotAlert() {
    webhook("payment.authorized", rzpOrder, false);
    assertThat(authorizedStamped(orderId)).isTrue();

    watch.runOnce();

    assertThat(alerts("RAZORPAY NOT CAPTURED")).isZero();
  }

  @Test
  @DisplayName("captured before authorized: the late authorized event stamps nothing, no alert")
  void capturedBeforeAuthorized() {
    webhook("payment.captured", rzpOrder, true);
    assertThat(status(orderId)).isEqualTo("SUCCEEDED");

    webhook("payment.authorized", rzpOrder, false);
    jdbc.sql("UPDATE payment.payments SET authorized_at = now() - interval '1 hour'")
        .update(); // even if a stamp existed, the alert-time status guard holds
    watch.runOnce();

    assertThat(authorizedStamped(orderId)).isTrue(); // forced above; the stamp itself was skipped
    assertThat(alerts("RAZORPAY NOT CAPTURED")).isZero();
  }

  @Test
  @DisplayName("authorized with captured=true is not even stamped, so it never alerts")
  void authorizedAlreadyCaptured() {
    webhook("payment.authorized", rzpOrder, true);

    assertThat(authorizedStamped(orderId)).isFalse();
    watch.runOnce();
    assertThat(alerts("RAZORPAY NOT CAPTURED")).isZero();
  }

  @Test
  @DisplayName("authorized, then captured within the grace period: no alert")
  void capturedWithinGrace() {
    webhook("payment.authorized", rzpOrder, false);
    webhook("payment.captured", rzpOrder, true);
    age(orderId, 60);

    watch.runOnce();

    assertThat(status(orderId)).isEqualTo("SUCCEEDED");
    assertThat(alerts("RAZORPAY NOT CAPTURED")).isZero();
  }

  @Test
  @DisplayName(
      "the late authorized event after capture stamps nothing (status guard at stamp time)")
  void stampSkippedAfterCapture() {
    webhook("payment.captured", rzpOrder, true);
    webhook("payment.authorized", rzpOrder, false);

    assertThat(authorizedStamped(orderId)).isFalse();
  }

  @Test
  @DisplayName("a payment of another mode is never alerted")
  void otherModesNeverAlerted() {
    UUID stripeOrder = UUID.randomUUID();
    insertPayment(stripeOrder, "order_s_" + stripeOrder, "STRIPE", "REQUIRES_PAYMENT");
    jdbc.sql(
            "UPDATE payment.payments SET authorized_at = now() - interval '1 hour' WHERE order_id = :o")
        .param("o", stripeOrder)
        .update();

    watch.runOnce();

    assertThat(
            logs.list.stream()
                .filter(e -> e.getFormattedMessage().contains(stripeOrder.toString()))
                .count())
        .isZero();
  }

  @Test
  @DisplayName("a refund stuck PENDING alerts once; a second tick does not repeat it")
  void stuckRefund() {
    UUID paymentId =
        jdbc.sql("SELECT id FROM payment.payments WHERE order_id = :o")
            .param("o", orderId)
            .query(UUID.class)
            .single();
    jdbc.sql(
            "INSERT INTO payment.refunds (payment_id, amount, provider_refund_id, idempotency_key, "
                + "status, created_at) VALUES (:p, 10, 'rfnd_stuck', 'k-stuck', 'PENDING', "
                + "now() - interval '11 days')")
        .param("p", paymentId)
        .update();
    jdbc.sql(
            "INSERT INTO payment.refunds (payment_id, amount, provider_refund_id, idempotency_key, "
                + "status) VALUES (:p, 10, 'rfnd_fresh', 'k-fresh', 'PENDING')")
        .param("p", paymentId)
        .update();

    watch.runOnce();
    watch.runOnce();

    assertThat(alerts("REFUND PENDING TOO LONG")).isEqualTo(1);
    assertThat(
            logs.list.stream().filter(e -> e.getFormattedMessage().contains("rfnd_fresh")).count())
        .isZero();
  }
}
