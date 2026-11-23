package com.acme.ecommerce.order.service;

import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient.CreatePayment;
import com.acme.ecommerce.order.client.PaymentFeignClient.PaymentRow;
import com.acme.ecommerce.order.outbox.Outbox;
import com.acme.ecommerce.order.outbox.Outbox.Kind;
import com.acme.ecommerce.order.service.OrderStore.CodRowSpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Every COD order gets a payment-service row (B-10). Placement creates it inline; when that fails
 * an ENSURE_COD_PAYMENT outbox row, written in the order's own transaction, repairs it later. The
 * repair row has its own aggregate so a payment-service outage never holds back the order's events.
 * {@link #ensure} is idempotent and holds no transaction around remote calls.
 */
@Service
public class CodPaymentRows {

  private static final Logger log = LoggerFactory.getLogger(CodPaymentRows.class);

  /** The repair waits this long, so it cannot race the inline create of the placement. */
  static final Duration REPAIR_DELAY = Duration.ofSeconds(60);

  public enum Outcome {
    SKIPPED_NOT_COD,
    SKIPPED_CANCELLED,
    SKIPPED_BAD_AMOUNT,
    CONFLICT_METHOD,
    EXISTING,
    CREATED
  }

  /** Outbox payload: just the order id. */
  public record Payload(UUID orderId) {}

  private final Outbox outbox;
  private final OrderStore store;
  private final PaymentFeignClient payments;

  public CodPaymentRows(Outbox outbox, OrderStore store, PaymentFeignClient payments) {
    this.outbox = outbox;
    this.store = store;
    this.payments = payments;
  }

  static String key(UUID orderId) {
    return Kind.ENSURE_COD_PAYMENT + ":" + orderId;
  }

  /** Records the repair (call inside the order's save transaction). */
  public void enqueueRepair(UUID orderId) {
    UUID aggregate =
        UUID.nameUUIDFromBytes(("cod-payment:" + orderId).getBytes(StandardCharsets.UTF_8));
    outbox.enqueue(
        Kind.ENSURE_COD_PAYMENT, aggregate, new Payload(orderId), key(orderId), REPAIR_DELAY);
  }

  /** The inline create worked: the repair is not needed. */
  public void repairDone(UUID orderId) {
    outbox.complete(key(orderId));
  }

  /**
   * Makes sure the COD order has a payment row and its payment_id. Transient remote errors throw
   * (the relay retries); every other outcome returns, so the repair row goes DONE.
   */
  public Outcome ensure(UUID orderId) {
    Optional<CodRowSpec> found = store.codRowSpec(orderId);
    if (found.isEmpty() || !found.get().cod()) {
      return Outcome.SKIPPED_NOT_COD;
    }
    CodRowSpec spec = found.get();
    if (spec.cancelled()) {
      return Outcome.SKIPPED_CANCELLED;
    }
    if (spec.paymentId() != null) {
      return Outcome.EXISTING;
    }
    if (spec.remainder().signum() <= 0) {
      log.error(
          "COD order {} has no payable remainder ({}); no row made", orderId, spec.remainder());
      return Outcome.SKIPPED_BAD_AMOUNT;
    }
    PaymentRow row =
        payments.ensureRow(
            new CreatePayment(orderId, spec.userId(), spec.remainder(), spec.currency(), "COD"));
    if (!"COD".equals(row.method())) {
      log.error(
          "COD order {} already has a {} payment row {}; left unchanged",
          orderId,
          row.method(),
          row.paymentId());
      return Outcome.CONFLICT_METHOD;
    }
    BigDecimal existing = row.amount();
    if (existing != null && existing.compareTo(spec.remainder()) != 0) {
      log.warn(
          "COD order {} row {} amount {} differs from the order remainder {}; existing row wins",
          orderId,
          row.paymentId(),
          existing,
          spec.remainder());
    }
    return store.attachPaymentIfAbsent(orderId, row.paymentId())
        ? Outcome.CREATED
        : Outcome.EXISTING;
  }
}
