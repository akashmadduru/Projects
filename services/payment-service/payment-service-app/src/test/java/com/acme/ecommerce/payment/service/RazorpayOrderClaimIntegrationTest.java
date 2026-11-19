package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.payment.api.dto.CreatePaymentRequest;
import com.acme.ecommerce.payment.api.dto.PaymentResponse;
import com.acme.ecommerce.payment.support.AbstractPaymentIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * AC-9: one Razorpay order per payment. Razorpay has no idempotency key for orders, so the row is
 * inserted first, one caller creates the order and publishes it conditionally, and the others wait.
 * Razorpay HTTP is stubbed through the gateway seam. The lease is 2 s so takeover is testable.
 */
@TestPropertySource(
    properties = {
      "payment.razorpay.key-id=rzp_test_id",
      "payment.razorpay.key-secret=rzp_secret",
      "payment.razorpay.connect-timeout=500ms",
      "payment.razorpay.read-timeout=500ms",
      "payment.razorpay.provision-lease=2s"
    })
class RazorpayOrderClaimIntegrationTest extends AbstractPaymentIntegrationTest {

  @Autowired private PaymentService service;
  @Autowired private JdbcClient jdbc;
  @MockitoSpyBean private RazorpayGateway razorpay;

  private static CreatePaymentRequest request(UUID subject, String method) {
    return new CreatePaymentRequest(
        subject, UUID.randomUUID(), new BigDecimal("499"), "inr", method, null, null, null);
  }

  private static RazorpayGateway.Order order(String id) {
    return new RazorpayGateway.Order(id, "created", 49900, "INR");
  }

  private long paymentRows(UUID subject) {
    return jdbc.sql("SELECT count(*) FROM payment.payments WHERE order_id = :o")
        .param("o", subject)
        .query(Long.class)
        .single();
  }

  private String storedOrder(UUID subject) {
    return jdbc.sql("SELECT provider_order_id FROM payment.payments WHERE order_id = :o")
        .param("o", subject)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Test
  @DisplayName("8 concurrent creates call Razorpay once, make one row, and all get the same order")
  void concurrentCreatesMakeOneOrder() throws Exception {
    UUID subject = UUID.randomUUID();
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            i -> {
              int n = calls.incrementAndGet();
              Thread.sleep(400);
              return order("order_conc_" + n);
            })
        .when(razorpay)
        .createOrder(anyString(), anyLong(), anyString());
    int threads = 8;
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<PaymentResponse>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      results.add(
          pool.submit(
              () -> {
                go.await();
                return service.create(request(subject, "CARD"));
              }));
    }
    go.countDown();

    List<String> ids = new ArrayList<>();
    for (var f : results) {
      PaymentResponse r = f.get();
      assertThat(r.status()).isEqualTo("REQUIRES_PAYMENT");
      ids.add(r.providerOrderId());
    }
    pool.shutdown();

    assertThat(calls.get()).isEqualTo(1);
    assertThat(paymentRows(subject)).isEqualTo(1);
    assertThat(ids).doesNotContainNull().containsOnly("order_conc_1");
    assertThat(storedOrder(subject)).isEqualTo("order_conc_1");
  }

  @Test
  @DisplayName("a repeated create after the order exists makes no second order")
  void repeatedCreateIsIdempotent() {
    UUID subject = UUID.randomUUID();
    doAnswer(i -> order("order_once"))
        .when(razorpay)
        .createOrder(anyString(), anyLong(), anyString());

    service.create(request(subject, "CARD"));
    var again = service.create(request(subject, "CARD"));

    assertThat(again.providerOrderId()).isEqualTo("order_once");
    verify(razorpay, org.mockito.Mockito.times(1)).createOrder(anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("createOrder failing is a 503, leaves no order, and the lease is taken over later")
  void failureThenLeaseReclaim() throws Exception {
    UUID subject = UUID.randomUUID();
    doAnswer(
            i -> {
              throw new CodedException(ErrorCode.UPSTREAM_UNAVAILABLE, "down");
            })
        .when(razorpay)
        .createOrder(anyString(), anyLong(), anyString());

    assertThatThrownBy(() -> service.create(request(subject, "CARD")))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));

    assertThat(paymentRows(subject)).isEqualTo(1);
    assertThat(storedOrder(subject)).isNull();
    assertThat(service.getInternal(subject).providerOrderId()).isNull();
    UUID user =
        jdbc.sql("SELECT user_id FROM payment.payments WHERE order_id = :o")
            .param("o", subject)
            .query(UUID.class)
            .single();
    service.getForOwner(subject, user);
    verify(razorpay, never()).capturedPayment(anyString());

    // The failed caller released the lease, so an immediate retry provisions without waiting.
    doAnswer(i -> order("order_retry"))
        .when(razorpay)
        .createOrder(anyString(), anyLong(), anyString());
    long start = System.nanoTime();
    var res = service.create(request(subject, "CARD"));
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);

    assertThat(res.providerOrderId()).isEqualTo("order_retry");
    assertThat(paymentRows(subject)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "CTO 3: a late original winner's publish matches no row and its order is unreachable")
  void lateWinnerCannotPublish() throws Exception {
    UUID subject = UUID.randomUUID();
    CountDownLatch winnerInside = new CountDownLatch(1);
    CountDownLatch releaseWinner = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            i -> {
              if (calls.incrementAndGet() == 1) {
                winnerInside.countDown();
                releaseWinner.await(); // the original winner is stuck past its lease
                return order("order_late");
              }
              return order("order_takeover");
            })
        .when(razorpay)
        .createOrder(anyString(), anyLong(), anyString());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<PaymentResponse> original = pool.submit(() -> service.create(request(subject, "CARD")));
    assertThat(winnerInside.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    // Another caller waits out the 2 s lease, takes over and publishes its own order.
    var taker = service.create(request(subject, "CARD"));
    assertThat(taker.providerOrderId()).isEqualTo("order_takeover");
    releaseWinner.countDown();

    // The late winner's conditional publish returns 0 and it answers with the stored order.
    PaymentResponse late = original.get();
    pool.shutdown();
    assertThat(late.providerOrderId()).isEqualTo("order_takeover");
    assertThat(storedOrder(subject)).isEqualTo("order_takeover");
    assertThat(
            jdbc.sql("SELECT count(*) FROM payment.payments WHERE provider_order_id = 'order_late'")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("publish is guarded: a payment no longer awaiting Razorpay gets no order id")
  void publishIsGuarded() {
    UUID subject = UUID.randomUUID();
    doAnswer(
            i -> {
              jdbc.sql("UPDATE payment.payments SET status = 'FAILED' WHERE order_id = :o")
                  .param("o", subject)
                  .update();
              return order("order_unpublishable");
            })
        .when(razorpay)
        .createOrder(anyString(), anyLong(), anyString());

    var res = service.create(request(subject, "CARD"));

    assertThat(res.providerOrderId()).isNull();
    assertThat(storedOrder(subject)).isNull();
  }

  @Test
  @DisplayName("COD create is unchanged: no Razorpay call, no lease")
  void codUnchanged() {
    UUID subject = UUID.randomUUID();

    var res = service.create(request(subject, "COD"));

    assertThat(res.status()).isEqualTo("COD_PENDING");
    verify(razorpay, never()).createOrder(anyString(), anyLong(), anyString());
    assertThat(
            jdbc.sql(
                    "SELECT provision_claimed_at IS NULL FROM payment.payments WHERE order_id = :o")
                .param("o", subject)
                .query(Boolean.class)
                .single())
        .isTrue();
  }
}
