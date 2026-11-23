package com.acme.ecommerce.order.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient.CreatePayment;
import com.acme.ecommerce.order.client.PaymentFeignClient.CreditLookup;
import com.acme.ecommerce.order.client.PaymentFeignClient.PaymentRow;
import com.acme.ecommerce.order.client.RemoteCalls;
import com.acme.ecommerce.order.outbox.Outbox;
import com.acme.ecommerce.order.outbox.Outbox.Kind;
import com.acme.ecommerce.order.service.OrderStore.CodRowSpec;
import com.acme.ecommerce.order.service.OrderStore.ReturnRefundPlan;
import feign.FeignException;
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
  private final OrderRemote remote;

  public CodPaymentRows(
      Outbox outbox, OrderStore store, PaymentFeignClient payments, OrderRemote remote) {
    this.remote = remote;
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
    if (found.isEmpty()) {
      log.warn("ensure: order {} not found; nothing to do", orderId);
      return Outcome.SKIPPED_NOT_COD;
    }
    if (!found.get().cod()) {
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

  /**
   * Delivery of a COD order: ensure the row first (a legacy order has none), then mark it
   * collected, only when the row is ours (CREATED or EXISTING) and the order really is collected. A
   * cancelled or undelivered order gets no mark and no money moves.
   */
  public Outcome collect(UUID orderId) {
    Outcome outcome = ensure(orderId);
    if ((outcome == Outcome.CREATED || outcome == Outcome.EXISTING)
        && store.codRowSpec(orderId).map(CodRowSpec::collected).orElse(false)) {
      remote.codCollectedNow(orderId);
    }
    return outcome;
  }

  /**
   * Before a COD refund goes to payment-service (route PAYMENT_WALLET), on every attempt: was this
   * return already paid by the pre-F-09 wallet fallback (credit {@code return-rest-<returnId>})?
   *
   * @return true when that credit exists and matches exactly (same user, amount by compareTo, same
   *     currency): the rest leg is done and must not be sent again. A different credit is a 409
   *     with no money moved. Otherwise the row is ensured (and collect-marked when collected).
   */
  public boolean prepareRefund(ReturnRefundPlan plan) {
    CodRowSpec spec = store.codRowSpec(plan.orderId()).orElse(null);
    if (spec == null || !spec.cod()) {
      return false;
    }
    try {
      CreditLookup credit = null;
      boolean oldPaymentService = false;
      try {
        credit = payments.creditLookup("return-rest-" + plan.returnId());
      } catch (FeignException.NotFound e) {
        oldPaymentService = true;
      }
      if (credit != null && credit.found()) {
        if (plan.userId().equals(credit.userId())
            && credit.amount() != null
            && credit.amount().compareTo(plan.rest()) == 0
            && plan.currency().equals(credit.currency())) {
          return true;
        }
        log.error(
            "COD LEGACY CREDIT MISMATCH: return {} of order {} has a return-rest credit that differs"
                + " from the frozen rest leg; a human must settle it",
            plan.returnId(),
            plan.orderId());
        throw new CodedException(
            ErrorCode.RESOURCE_CONFLICT, "A different legacy refund credit exists for this return");
      }
      if (oldPaymentService && spec.paymentId() == null) {
        throw new CodedException(
            ErrorCode.DEPENDENCY_UNAVAILABLE, "payment-service cannot confirm the legacy credit");
      }
      Outcome outcome = ensure(plan.orderId());
      if (outcome != Outcome.CREATED && outcome != Outcome.EXISTING) {
        log.error("COD refund of return {} blocked: ensure gave {}", plan.returnId(), outcome);
        throw new CodedException(
            ErrorCode.RESOURCE_CONFLICT, "No usable COD payment row (" + outcome + ")");
      }
      if (spec.collected()) {
        remote.codCollectedNow(plan.orderId());
      }
      return false;
    } catch (FeignException e) {
      throw RemoteCalls.translate("payment-service", e);
    }
  }
}
