package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.payment.repository.PaymentRepository;
import com.acme.ecommerce.payment.support.AbstractPaymentIntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** V7: refund status column, its default for existing rows, and the parked-event table. */
class RefundMigrationIntegrationTest extends AbstractPaymentIntegrationTest {

  @Autowired private JdbcClient jdbc;
  @Autowired private PaymentRepository repo;

  private UUID payment() {
    UUID order = UUID.randomUUID();
    return jdbc.sql(
            "INSERT INTO payment.payments (order_id, user_id, method, mode, amount, currency, "
                + "status, purpose, reference_id) VALUES (:o, :u, 'CARD', 'RAZORPAY', 100, 'INR', "
                + "'SUCCEEDED', 'ORDER', :o) RETURNING id")
        .param("o", order)
        .param("u", UUID.randomUUID())
        .query(UUID.class)
        .single();
  }

  @Test
  @DisplayName("a refund inserted without a status (old pod, existing row) reads as SUCCEEDED")
  void defaultIsSucceeded() {
    UUID p = payment();
    UUID id =
        jdbc.sql(
                "INSERT INTO payment.refunds (payment_id, amount, idempotency_key) "
                    + "VALUES (:p, 10, :k) RETURNING id")
            .param("p", p)
            .param("k", "k-" + UUID.randomUUID())
            .query(UUID.class)
            .single();

    assertThat(repo.refundStatus(id)).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("the CHECK constraint rejects any other status")
  void checkRejectsOthers() {
    UUID p = payment();
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO payment.refunds (payment_id, amount, idempotency_key, status) "
                            + "VALUES (:p, 10, :k, 'DONE')")
                    .param("p", p)
                    .param("k", "k-" + UUID.randomUUID())
                    .update())
        .hasMessageContaining("ck_refunds_status");
  }

  @Test
  @DisplayName("the conditional settle changes a PENDING row once, then returns 0")
  void settleIsOnce() {
    UUID p = payment();
    repo.insertRefund(p, new BigDecimal("10.00"), null, null, "rfnd_m1", "k-" + p, "PENDING");
    UUID id = repo.findRefundByProviderRefundId("rfnd_m1").orElseThrow().id();

    assertThat(repo.settleRefundRow(id, "FAILED")).isEqualTo(1);
    assertThat(repo.settleRefundRow(id, "SUCCEEDED")).isZero();
    assertThat(repo.refundStatus(id)).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("parking the same event twice is idempotent, and taking it removes it")
  void parkedIsIdempotent() {
    repo.parkRefundEvent("rfnd_pk", "FAILED", "pay_1", 1000, "INR");
    repo.parkRefundEvent("rfnd_pk", "FAILED", "pay_1", 1000, "INR");

    assertThat(repo.takeParkedRefundEvents("rfnd_pk")).hasSize(1);
    assertThat(repo.takeParkedRefundEvents("rfnd_pk")).isEmpty();
  }
}
