package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * B-10 AC-4/AC-5: order-service ensures a COD row by calling create again. Create must give exactly
 * one row (first amount wins), move no money, call no provider, and keep COD_PENDING until the
 * delivery collect-mark.
 */
class CodEnsureConcurrencyIntegrationTest extends AbstractPaymentIntegrationTest {

  @Autowired private PaymentService service;
  @Autowired private JdbcClient jdbc;
  @MockitoSpyBean private RazorpayGateway razorpay;

  private static CreatePaymentRequest cod(UUID order, UUID user, String amount) {
    return new CreatePaymentRequest(
        order, user, new BigDecimal(amount), "INR", "COD", null, null, null);
  }

  private long rows(UUID order) {
    return jdbc.sql("SELECT count(*) FROM payment.payments WHERE order_id = :o")
        .param("o", order)
        .query(Long.class)
        .single();
  }

  private long walletTxns(UUID user) {
    return jdbc.sql("SELECT count(*) FROM payment.wallet_txns WHERE user_id = :u")
        .param("u", user)
        .query(Long.class)
        .single();
  }

  private String status(UUID order) {
    return jdbc.sql("SELECT status FROM payment.payments WHERE order_id = :o")
        .param("o", order)
        .query(String.class)
        .single();
  }

  @Test
  @DisplayName("AC-4: 8 threads with mixed amounts make one row; the first amount wins; no money")
  void eightThreadsOneRow() throws Exception {
    UUID order = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<PaymentResponse>> results = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String amount = (i % 2 == 0) ? "380.00" : "450.00";
      results.add(
          pool.submit(
              () -> {
                go.await();
                return service.create(cod(order, user, amount));
              }));
    }
    go.countDown();
    List<PaymentResponse> done = new ArrayList<>();
    for (Future<PaymentResponse> f : results) {
      done.add(f.get());
    }
    pool.shutdown();

    assertThat(rows(order)).isEqualTo(1);
    BigDecimal stored =
        jdbc.sql("SELECT amount FROM payment.payments WHERE order_id = :o")
            .param("o", order)
            .query(BigDecimal.class)
            .single();
    assertThat(stored).isIn(new BigDecimal("380.0000"), new BigDecimal("450.0000"));
    // Every caller sees the stored row: the same id and amount, never its own losing amount.
    assertThat(done)
        .allSatisfy(
            r -> {
              assertThat(r.paymentId()).isEqualTo(done.get(0).paymentId());
              assertThat(r.amount()).isEqualByComparingTo(stored);
              assertThat(r.status()).isEqualTo("COD_PENDING");
              assertThat(r.method()).isEqualTo("COD");
            });
    assertThat(walletTxns(user)).isZero();
    verifyNoInteractions(razorpay);
  }

  @Test
  @DisplayName("AC-4: create after create keeps the first amount")
  void secondCreateKeepsFirstAmount() {
    UUID order = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    service.create(cod(order, user, "380.00"));

    PaymentResponse again = service.create(cod(order, user, "450.00"));

    assertThat(rows(order)).isEqualTo(1);
    assertThat(again.amount()).isEqualByComparingTo("380.00");
  }

  @Test
  @DisplayName(
      "AC-5: COD stays COD_PENDING through create and cancelUnpaid; only codCollected pays")
  void staysPendingUntilCollected() {
    UUID order = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    service.create(cod(order, user, "380.00"));
    assertThat(status(order)).isEqualTo("COD_PENDING");

    service.cancelUnpaid(order);
    assertThat(status(order)).isEqualTo("COD_PENDING");

    service.create(cod(order, user, "380.00"));
    assertThat(status(order)).isEqualTo("COD_PENDING");

    service.codCollected(order);
    assertThat(status(order)).isEqualTo("SUCCEEDED");
    assertThat(walletTxns(user)).isZero();
    verifyNoInteractions(razorpay);
  }

  @Test
  @DisplayName("control: the wallet count query sees a transaction when there is one")
  void walletCountQueryDetectsARow() {
    UUID user = UUID.randomUUID();
    assertThat(walletTxns(user)).isZero();
    jdbc.sql(
            "INSERT INTO payment.wallet_txns (user_id, type, amount, currency, reason,"
                + " idempotency_key, balance_after) VALUES (:u, 'CREDIT', 1, 'INR', 'REFUND',"
                + " :k, 1)")
        .param("u", user)
        .param("k", "control-" + user)
        .update();
    assertThat(walletTxns(user)).isEqualTo(1);
  }
}
