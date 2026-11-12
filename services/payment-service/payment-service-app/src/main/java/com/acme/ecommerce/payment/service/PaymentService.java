package com.acme.ecommerce.payment.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.payment.api.dto.CreatePaymentRequest;
import com.acme.ecommerce.payment.api.dto.PaymentConfigResponse;
import com.acme.ecommerce.payment.api.dto.PaymentResponse;
import com.acme.ecommerce.payment.api.dto.RefundRequest;
import com.acme.ecommerce.payment.api.dto.RefundResponse;
import com.acme.ecommerce.payment.config.PaymentProperties;
import com.acme.ecommerce.payment.repository.PaymentRepository;
import com.acme.ecommerce.payment.repository.PaymentRepository.Payment;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Payment use cases. Stripe calls are never made inside a DB transaction. */
@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  private final PaymentRepository payments;
  private final StripeGateway stripe;
  private final OrderReporter reporter;
  private final PaymentProperties properties;
  private final TransactionTemplate tx;

  public PaymentService(
      PaymentRepository payments,
      StripeGateway stripe,
      OrderReporter reporter,
      PaymentProperties properties,
      TransactionTemplate tx) {
    this.payments = payments;
    this.stripe = stripe;
    this.reporter = reporter;
    this.properties = properties;
    this.tx = tx;
  }

  public PaymentConfigResponse config() {
    String key = stripe.enabled() ? properties.getStripe().getPublishableKey() : null;
    return new PaymentConfigResponse(stripe.mode(), key);
  }

  /** Idempotent by orderId. */
  public PaymentResponse create(CreatePaymentRequest req) {
    var existing = payments.findByOrderId(req.orderId());
    if (existing.isPresent()) {
      return view(existing.get());
    }
    String currency = req.currency().toUpperCase(Locale.ROOT);
    BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
    String mode = stripe.mode();
    String status;
    String intentId = null;
    if ("COD".equals(req.method())) {
      status = "COD_PENDING";
    } else {
      status = "REQUIRES_PAYMENT";
      if (stripe.enabled()) {
        intentId = stripe.createIntent(req.orderId().toString(), toMinor(amount), currency).id();
      }
    }
    payments.insert(
        req.orderId(), req.userId(), req.method(), mode, amount, currency, status, intentId);
    return view(
        payments
            .findByOrderId(req.orderId())
            .orElseThrow(() -> new CodedException(ErrorCode.INTERNAL_ERROR, "Payment not saved")));
  }

  /** Internal read (no ownership check). */
  public PaymentResponse getInternal(UUID orderId) {
    return view(find(orderId));
  }

  /**
   * Owner read. Also reconciles: re-checks the PaymentIntent with Stripe (local dev without
   * webhooks) and retries reporting an unreported outcome to order-service.
   */
  public PaymentResponse getForOwner(UUID orderId, UUID userId) {
    Payment p = find(orderId);
    if (!p.userId().equals(userId)) {
      throw notFound();
    }
    p = reconcile(p);
    return view(p);
  }

  /** SIMULATED mode only; same downstream effect as the webhook. */
  public PaymentResponse simulate(UUID paymentId, UUID userId, String outcome) {
    Payment p = payments.findById(paymentId).orElseThrow(PaymentService::notFound);
    if (!p.userId().equals(userId) || stripe.enabled() || !"SIMULATED".equals(p.mode())) {
      throw notFound();
    }
    if (!"CARD".equals(p.method())) {
      throw new CodedException(ErrorCode.RESOURCE_CONFLICT, "Only card payments can be simulated");
    }
    return view(applyOutcome(p, outcome));
  }

  private Payment reconcile(Payment p) {
    if ("REQUIRES_PAYMENT".equals(p.status()) || "PROCESSING".equals(p.status())) {
      if ("STRIPE".equals(p.mode()) && p.intentId() != null && stripe.enabled()) {
        try {
          String s = stripe.retrieve(p.intentId()).status();
          if ("succeeded".equals(s)) {
            return applyOutcome(p, "SUCCEEDED");
          } else if ("canceled".equals(s)) {
            return applyOutcome(p, "FAILED");
          }
        } catch (RuntimeException e) {
          log.warn("Stripe re-check failed for payment {}: {}", p.id(), e.getMessage());
        }
      }
    } else if (!p.reported()) {
      reporter.report(p);
      return payments.findById(p.id()).orElse(p);
    }
    return p;
  }

  private Payment applyOutcome(Payment p, String outcome) {
    if (payments.applyOutcome(p.id(), outcome) == 0) {
      log.info("Payment {} already has outcome {}; ignoring {}", p.id(), p.status(), outcome);
    }
    Payment fresh = payments.findById(p.id()).orElse(p);
    reporter.report(fresh);
    return payments.findById(p.id()).orElse(fresh);
  }

  /** Verifies the signature, dedupes by event id, applies the outcome. */
  public void handleWebhook(String payload, String signature) {
    String secret = properties.getStripe().getWebhookSecret();
    if (!stripe.enabled() || secret == null || secret.isBlank()) {
      throw new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Webhook not configured");
    }
    Event event;
    try {
      event = Webhook.constructEvent(payload, signature == null ? "" : signature, secret);
    } catch (SignatureVerificationException e) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Invalid webhook signature");
    }
    String type = event.getType();
    if (!"payment_intent.succeeded".equals(type) && !"payment_intent.payment_failed".equals(type)) {
      return;
    }
    if (!payments.recordEvent(event.getId())) {
      log.info("Duplicate Stripe event {} ignored", event.getId());
      return;
    }
    PaymentIntent pi;
    try {
      pi = (PaymentIntent) event.getDataObjectDeserializer().deserializeUnsafe();
    } catch (Exception e) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Unreadable webhook payload");
    }
    Payment p = payments.findByIntentId(pi.getId()).orElse(null);
    if (p == null && pi.getMetadata() != null && pi.getMetadata().get("orderId") != null) {
      p = payments.findByOrderId(UUID.fromString(pi.getMetadata().get("orderId"))).orElse(null);
    }
    if (p == null) {
      log.warn("Stripe event {} for unknown payment intent {}", event.getId(), pi.getId());
      return;
    }
    applyOutcome(p, "payment_intent.succeeded".equals(type) ? "SUCCEEDED" : "FAILED");
  }

  /** Idempotent by idempotencyKey. */
  public RefundResponse refund(UUID orderId, RefundRequest req) {
    Payment p = find(orderId);
    if (payments.refundExists(req.idempotencyKey())) {
      return refundView(payments.findById(p.id()).orElse(p));
    }
    if ("COD_PENDING".equals(p.status())
        || "REQUIRES_PAYMENT".equals(p.status())
        || "PROCESSING".equals(p.status())
        || "FAILED".equals(p.status())) {
      throw new CodedException(ErrorCode.RESOURCE_CONFLICT, "Payment has not been collected");
    }
    BigDecimal remaining = p.amount().subtract(p.refundedAmount());
    BigDecimal amount =
        req.amount() == null ? remaining : req.amount().setScale(2, RoundingMode.HALF_UP);
    if (amount.signum() <= 0 || amount.compareTo(remaining) > 0) {
      throw new CodedException(
          ErrorCode.DOMAIN_RULE_VIOLATION, "Refund amount exceeds the refundable balance");
    }
    String stripeRefundId = null;
    if ("STRIPE".equals(p.mode()) && "CARD".equals(p.method()) && p.intentId() != null) {
      stripeRefundId = stripe.refund(p.intentId(), toMinor(amount), req.idempotencyKey());
    }
    String refundId = stripeRefundId;
    return tx.execute(
        status -> {
          Payment locked = payments.lockById(p.id()).orElseThrow();
          if (payments.refundExists(req.idempotencyKey())) {
            return refundView(locked);
          }
          BigDecimal total = locked.refundedAmount().add(amount);
          if (total.compareTo(locked.amount()) > 0) {
            throw new CodedException(
                ErrorCode.DOMAIN_RULE_VIOLATION, "Refund amount exceeds the refundable balance");
          }
          payments.insertRefund(locked.id(), amount, req.reason(), refundId, req.idempotencyKey());
          String newStatus =
              total.compareTo(locked.amount()) == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
          payments.applyRefund(locked.id(), total, newStatus);
          return new RefundResponse(newStatus, total);
        });
  }

  /** COD_PENDING to SUCCEEDED; idempotent. */
  public PaymentResponse codCollected(UUID orderId) {
    Payment p = find(orderId);
    if (!"COD".equals(p.method())) {
      throw new CodedException(ErrorCode.RESOURCE_CONFLICT, "Not a cash-on-delivery payment");
    }
    payments.markCodCollected(p.id());
    return view(payments.findById(p.id()).orElse(p));
  }

  private Payment find(UUID orderId) {
    return payments.findByOrderId(orderId).orElseThrow(PaymentService::notFound);
  }

  private static CodedException notFound() {
    return new CodedException(ErrorCode.PAYMENT_NOT_FOUND, "Payment not found");
  }

  private static long toMinor(BigDecimal amount) {
    return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
  }

  private static RefundResponse refundView(Payment p) {
    return new RefundResponse(p.status(), p.refundedAmount());
  }

  private PaymentResponse view(Payment p) {
    String clientSecret = null;
    String publishable = null;
    boolean awaiting = "REQUIRES_PAYMENT".equals(p.status()) || "PROCESSING".equals(p.status());
    if (awaiting && "STRIPE".equals(p.mode()) && p.intentId() != null && stripe.enabled()) {
      try {
        clientSecret = stripe.retrieve(p.intentId()).clientSecret();
        publishable = properties.getStripe().getPublishableKey();
      } catch (RuntimeException e) {
        log.warn("Could not load client secret for payment {}: {}", p.id(), e.getMessage());
      }
    }
    return new PaymentResponse(
        p.id(),
        p.orderId(),
        p.status(),
        p.mode(),
        p.method(),
        p.amount(),
        p.currency(),
        p.refundedAmount(),
        clientSecret,
        publishable);
  }
}
