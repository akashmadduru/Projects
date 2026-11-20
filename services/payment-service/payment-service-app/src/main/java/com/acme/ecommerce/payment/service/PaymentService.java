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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment use cases. Provider calls (Stripe, Razorpay) are never made inside a DB transaction.
 * Stripe and Razorpay can both be on; each payment records which one it uses and the customer can
 * switch while it is still unpaid.
 */
@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final PaymentRepository payments;
  private final StripeGateway stripe;
  private final RazorpayGateway razorpay;
  private final PaymentProviders providers;
  private final OrderReporter reporter;
  private final WalletService wallet;
  private final PaymentProperties properties;
  private final TransactionTemplate tx;

  /**
   * Provider re-checks are synchronous HTTP calls made while the customer waits on the order page.
   * Each provider object (Stripe intent or Razorpay order id) is re-checked at most once per
   * interval, and not at all just after it was created (it cannot be paid yet). Per pod, in memory:
   * a missed re-check only delays settlement until the next load or the webhook.
   */
  static final long RECHECK_INTERVAL_MS = 10_000;

  private static final int RECHECK_MAX_TRACKED = 10_000;
  private final ConcurrentHashMap<String, Long> lastRecheck = new ConcurrentHashMap<>();
  LongSupplier clock = () -> System.nanoTime() / 1_000_000;

  public PaymentService(
      PaymentRepository payments,
      StripeGateway stripe,
      RazorpayGateway razorpay,
      PaymentProviders providers,
      OrderReporter reporter,
      WalletService wallet,
      PaymentProperties properties,
      TransactionTemplate tx) {
    this.payments = payments;
    this.stripe = stripe;
    this.razorpay = razorpay;
    this.providers = providers;
    this.reporter = reporter;
    this.wallet = wallet;
    this.properties = properties;
    this.tx = tx;
  }

  public PaymentConfigResponse config() {
    List<PaymentConfigResponse.Provider> list = new ArrayList<>(2);
    if (stripe.enabled()) {
      list.add(
          new PaymentConfigResponse.Provider(
              PaymentProviders.STRIPE,
              "Card",
              properties.getStripe().getPublishableKey(),
              List.of("card"),
              List.of()));
    }
    if (razorpay.enabled()) {
      list.add(
          new PaymentConfigResponse.Provider(
              PaymentProviders.RAZORPAY,
              "UPI, cards, netbanking, wallets",
              razorpay.keyId(),
              List.of("upi", "card", "netbanking", "wallet"),
              List.of("INR")));
    }
    String mode = providers.defaultProvider();
    String key =
        list.stream()
            .filter(p -> p.id().equals(mode))
            .map(PaymentConfigResponse.Provider::publicKey)
            .findFirst()
            .orElse(null);
    return new PaymentConfigResponse(mode, key, list, list.isEmpty() ? null : mode);
  }

  /** Idempotent by subject id (orderId, or referenceId for MEMBERSHIP / GIFT_CARD). */
  public PaymentResponse create(CreatePaymentRequest req) {
    UUID subject = req.subjectId();
    if (subject == null) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "orderId or referenceId is required");
    }
    String purpose = req.effectivePurpose();
    if (!"ORDER".equals(purpose) && !"CARD".equals(req.method())) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, purpose + " payments must be CARD");
    }
    var existing = payments.findByOrderId(subject);
    if (existing.isPresent()) {
      Payment e = existing.get();
      return view(awaitsRazorpayOrder(e) ? awaitRazorpayOrder(e) : e);
    }
    String currency = req.currency().toUpperCase(Locale.ROOT);
    BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
    String mode;
    String status;
    String intentId = null;
    String providerOrderId = null;
    if ("COD".equals(req.method())) {
      status = "COD_PENDING";
      mode = providers.defaultProvider();
    } else {
      status = "REQUIRES_PAYMENT";
      mode = pickProvider(req.provider(), currency);
      if (PaymentProviders.RAZORPAY.equals(mode)) {
        return view(createRazorpay(req, amount, currency, purpose));
      }
      String[] ids = provision(mode, subject.toString(), amount, currency, null);
      intentId = ids[0];
      providerOrderId = ids[1];
    }
    payments.insert(
        subject,
        req.userId(),
        req.method(),
        mode,
        amount,
        currency,
        status,
        intentId,
        providerOrderId,
        purpose);
    return view(
        payments
            .findByOrderId(subject)
            .orElseThrow(() -> new CodedException(ErrorCode.INTERNAL_ERROR, "Payment not saved")));
  }

  // ---- one Razorpay order per payment ------------------------------------------------------
  // Razorpay has no idempotency key for orders, so the row is inserted first (the unique order_id
  // index lets exactly one caller win), that caller creates the order outside any transaction and
  // publishes it with a conditional UPDATE. Other callers wait for the id. If the winner dies, its
  // lease runs out and a waiter takes over; the late original's publish then matches no row, so its
  // order is never handed to anyone (an orphan on Razorpay's side, never a second payable order).

  private static final long POLL_MS = 100;

  private boolean awaitsRazorpayOrder(Payment p) {
    return PaymentProviders.RAZORPAY.equals(p.mode())
        && "CARD".equals(p.method())
        && "REQUIRES_PAYMENT".equals(p.status())
        && p.providerOrderId() == null
        && razorpay.enabled();
  }

  private Payment createRazorpay(
      CreatePaymentRequest req, BigDecimal amount, String currency, String purpose) {
    UUID subject = req.subjectId();
    var claimed =
        payments.insertClaimed(
            subject,
            req.userId(),
            req.method(),
            PaymentProviders.RAZORPAY,
            amount,
            currency,
            purpose);
    Payment row =
        payments
            .findByOrderId(subject)
            .orElseThrow(() -> new CodedException(ErrorCode.INTERNAL_ERROR, "Payment not saved"));
    if (claimed.isPresent()) {
      return publishRazorpayOrder(row, claimed.get());
    }
    return awaitsRazorpayOrder(row) ? awaitRazorpayOrder(row) : row;
  }

  private Payment publishRazorpayOrder(Payment p, java.time.OffsetDateTime lease) {
    String orderId;
    try {
      orderId =
          provision(
              PaymentProviders.RAZORPAY, p.orderId().toString(), p.amount(), p.currency(), null)[1];
    } catch (RuntimeException e) {
      // No order id was obtained: hand the lease back so a retry does not wait for it to expire.
      // A timeout counts as a failure too: the order may exist at Razorpay, and an orphan order
      // that nobody can reach is the accepted worst case (the publish is conditional).
      try {
        payments.releaseProvision(p.id(), lease);
      } catch (RuntimeException releaseFailure) {
        e.addSuppressed(releaseFailure);
      }
      throw e;
    }
    if (payments.publishProviderOrder(p.id(), orderId) == 0) {
      log.warn(
          "Razorpay order {} for payment {} was not published (another caller's order won); it is"
              + " left unused",
          orderId,
          p.id());
    }
    return payments.findById(p.id()).orElse(p);
  }

  /** Polls for the winner's order id; takes over when its lease runs out; 503 if neither. */
  private Payment awaitRazorpayOrder(Payment first) {
    var lease = properties.getRazorpay().getProvisionLease();
    double leaseSeconds = lease.toMillis() / 1000.0;
    long deadline = System.nanoTime() + lease.toNanos() + 500_000_000L;
    Payment cur = first;
    while (true) {
      cur = payments.findById(first.id()).orElse(cur);
      if (cur.providerOrderId() != null || !"REQUIRES_PAYMENT".equals(cur.status())) {
        return cur;
      }
      var mine = payments.reclaimProvision(cur.id(), leaseSeconds);
      if (mine.isPresent()) {
        return publishRazorpayOrder(cur, mine.get());
      }
      if (System.nanoTime() > deadline) {
        throw new CodedException(
            ErrorCode.UPSTREAM_UNAVAILABLE, "Payment provider is busy, please retry");
      }
      try {
        Thread.sleep(POLL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CodedException(ErrorCode.UPSTREAM_UNAVAILABLE, "Interrupted", e);
      }
    }
  }

  /**
   * Which provider a new online payment uses: the one asked for, else the default. Razorpay takes
   * INR only, so a non-INR payment falls back to another configured provider, or is refused if
   * Razorpay was asked for by name.
   */
  private String pickProvider(String requested, String currency) {
    String chosen = providers.resolve(requested);
    if (PaymentProviders.RAZORPAY.equals(chosen) && !"INR".equals(currency)) {
      boolean explicit = requested != null && !requested.isBlank();
      if (explicit || !providers.isEnabled(PaymentProviders.STRIPE)) {
        throw new CodedException(ErrorCode.VALIDATION_FAILED, "Razorpay accepts INR payments only");
      }
      return PaymentProviders.STRIPE;
    }
    return chosen;
  }

  /** Creates the provider-side object. Returns {stripeIntentId, razorpayOrderId}, nulls if none. */
  private String[] provision(
      String mode, String subject, BigDecimal amount, String currency, String stripeKey) {
    String[] ids = createProviderObject(mode, subject, amount, currency, stripeKey);
    for (String id : ids) {
      if (id != null) {
        lastRecheck.put(id, clock.getAsLong());
      }
    }
    return ids;
  }

  /** True at most once per interval per provider object; marks it as checked when true. */
  private boolean recheckDue(String providerObjectId) {
    long now = clock.getAsLong();
    boolean[] due = {false};
    lastRecheck.compute(
        providerObjectId,
        (k, prev) -> {
          if (prev != null && now - prev < RECHECK_INTERVAL_MS) {
            return prev;
          }
          due[0] = true;
          return now;
        });
    if (lastRecheck.size() > RECHECK_MAX_TRACKED) {
      lastRecheck.values().removeIf(t -> now - t >= RECHECK_INTERVAL_MS);
    }
    return due[0];
  }

  private String[] createProviderObject(
      String mode, String subject, BigDecimal amount, String currency, String stripeKey) {
    long minor = toMinor(amount);
    return switch (mode) {
      case PaymentProviders.STRIPE ->
          new String[] {
            (stripeKey == null
                    ? stripe.createIntent(subject, minor, currency)
                    : stripe.createIntent(subject, minor, currency, stripeKey))
                .id(),
            null
          };
      case PaymentProviders.RAZORPAY ->
          new String[] {null, razorpay.createOrder(subject, minor, currency).id()};
      default -> new String[] {null, null};
    };
  }

  /**
   * Internal: the order was cancelled or expired before it was paid (TD-P6-03). A Stripe intent is
   * cancelled so it can no longer be paid, then the payment is voided. A Razorpay order cannot be
   * cancelled, so it is left open: if the customer still pays it, the webhook settles it and
   * order-service refunds the late payment. If Stripe refuses the cancel (most likely it was paid a
   * moment ago), the payment is left as it is for the same late-payment path.
   */
  public PaymentResponse cancelUnpaid(UUID orderId) {
    Payment p = find(orderId);
    boolean unpaid = "REQUIRES_PAYMENT".equals(p.status()) || "PROCESSING".equals(p.status());
    if (!"CARD".equals(p.method()) || !unpaid) {
      return view(p);
    }
    if (PaymentProviders.RAZORPAY.equals(p.mode())) {
      log.info("Order {} cancelled before paying; Razorpay order left to lapse", orderId);
      return view(p);
    }
    if (PaymentProviders.STRIPE.equals(p.mode()) && p.intentId() != null && stripe.enabled()) {
      try {
        stripe.cancel(p.intentId());
      } catch (RuntimeException e) {
        log.warn(
            "Could not cancel Stripe intent {} for cancelled order {}; a late payment will be"
                + " refunded by order-service: {}",
            p.intentId(),
            orderId,
            e.getMessage());
        return view(p);
      }
    }
    payments.voidUnpaid(p.id());
    return view(payments.findById(p.id()).orElse(p));
  }

  /** Internal read (no ownership check). */
  public PaymentResponse getInternal(UUID orderId) {
    return view(find(orderId));
  }

  /**
   * Owner read. Also reconciles: re-checks the provider (local dev without webhooks, or a missed
   * webhook; throttled, see {@link #RECHECK_INTERVAL_MS}) and retries reporting an unreported
   * outcome to order-service.
   */
  public PaymentResponse getForOwner(UUID orderId, UUID userId) {
    Payment p = find(orderId);
    if (!p.userId().equals(userId)) {
      throw notFound();
    }
    p = reconcile(p);
    return view(p);
  }

  /**
   * The customer picks another provider before paying. Allowed only while the payment still awaits
   * money. The old Stripe intent is cancelled so it cannot be paid later; a Razorpay order cannot
   * be cancelled, so a payment captured against it afterwards is detected and logged for refund.
   */
  public PaymentResponse switchProvider(UUID subject, UUID userId, String requested) {
    Payment p = find(subject);
    if (!p.userId().equals(userId)) {
      throw notFound();
    }
    if (!"CARD".equals(p.method()) || !"REQUIRES_PAYMENT".equals(p.status())) {
      throw new CodedException(
          ErrorCode.RESOURCE_CONFLICT, "The payment method can only be changed before paying");
    }
    if (requested == null || requested.isBlank()) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "provider is required");
    }
    String target = pickProvider(requested, p.currency());
    if (PaymentProviders.SIMULATED.equals(target)) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "No payment provider is available");
    }
    if (target.equals(p.mode())) {
      return view(p);
    }
    String stripeKey = "pi-" + subject + "-" + UUID.randomUUID();
    String[] ids = provision(target, subject.toString(), p.amount(), p.currency(), stripeKey);
    if (payments.switchProvider(p.id(), target, ids[0], ids[1]) == 0) {
      // Paid or changed while we were talking to the provider: undo the new object, keep state.
      cancelStripeQuietly(ids[0]);
      return view(payments.findById(p.id()).orElse(p));
    }
    if (PaymentProviders.STRIPE.equals(p.mode())) {
      cancelStripeQuietly(p.intentId());
    }
    return view(payments.findById(p.id()).orElse(p));
  }

  private void cancelStripeQuietly(String intentId) {
    if (intentId == null || !stripe.enabled()) {
      return;
    }
    try {
      stripe.cancel(intentId);
    } catch (RuntimeException e) {
      log.warn("Could not cancel Stripe intent {}: {}", intentId, e.getMessage());
    }
  }

  /** SIMULATED mode only; same downstream effect as the webhook. */
  public PaymentResponse simulate(UUID paymentId, UUID userId, String outcome) {
    Payment p = payments.findById(paymentId).orElseThrow(PaymentService::notFound);
    if (!p.userId().equals(userId)
        || providers.anyEnabled()
        || !PaymentProviders.SIMULATED.equals(p.mode())) {
      throw notFound();
    }
    if (!"CARD".equals(p.method())) {
      throw new CodedException(ErrorCode.RESOURCE_CONFLICT, "Only card payments can be simulated");
    }
    return view(applyOutcome(p, outcome));
  }

  private Payment reconcile(Payment p) {
    if ("REQUIRES_PAYMENT".equals(p.status()) || "PROCESSING".equals(p.status())) {
      if (PaymentProviders.STRIPE.equals(p.mode())
          && p.intentId() != null
          && stripe.enabled()
          && recheckDue(p.intentId())) {
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
      } else if (PaymentProviders.RAZORPAY.equals(p.mode())
          && p.providerOrderId() != null
          && razorpay.enabled()
          && recheckDue(p.providerOrderId())) {
        try {
          // One call: the order's captured payment, if any (auto-capture marks the order paid).
          var captured = razorpay.capturedPayment(p.providerOrderId());
          if (captured != null) {
            if (captured.amount() != toMinor(p.amount())
                || !p.currency().equalsIgnoreCase(captured.currency())) {
              // Same rule as the webhook: never settle on an amount we did not ask for.
              log.error(
                  "Razorpay payment {} amount/currency does not match payment {}; not applying",
                  captured.id(),
                  p.id());
              return p;
            }
            flagIfSettledElsewhere(p, PaymentProviders.RAZORPAY, captured.id());
            payments.setProviderPaymentId(p.id(), captured.id());
            return applyOutcome(p, "SUCCEEDED");
          }
        } catch (RuntimeException e) {
          log.warn("Razorpay re-check failed for payment {}: {}", p.id(), e.getMessage());
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

  /**
   * A provider says money arrived for a payment that was already settled through another provider
   * (the customer switched and the abandoned attempt still went through). The platform cannot undo
   * it automatically, so it is logged loudly for a manual refund.
   */
  private void flagIfSettledElsewhere(Payment p, String provider, String providerPaymentId) {
    boolean settled =
        "SUCCEEDED".equals(p.status())
            || "REFUNDED".equals(p.status())
            || "PARTIALLY_REFUNDED".equals(p.status());
    boolean otherProvider = !provider.equals(p.mode());
    boolean otherPayment =
        provider.equals(p.mode())
            && PaymentProviders.RAZORPAY.equals(provider)
            && p.providerPaymentId() != null
            && providerPaymentId != null
            && !p.providerPaymentId().equals(providerPaymentId);
    if (settled && (otherProvider || otherPayment)) {
      log.error(
          "DOUBLE CHARGE: payment {} is already settled via {} but {} also received money ({}). "
              + "Refund the duplicate manually.",
          p.id(),
          p.mode(),
          provider,
          providerPaymentId);
    }
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
    boolean succeeded = "payment_intent.succeeded".equals(type);
    if (succeeded) {
      flagIfSettledElsewhere(p, PaymentProviders.STRIPE, pi.getId());
    }
    if (!PaymentProviders.STRIPE.equals(p.mode()) && !succeeded) {
      // A decline on an intent the customer has since moved away from must not fail the payment.
      log.info("Ignoring Stripe failure for payment {} now on {}", p.id(), p.mode());
      return;
    }
    applyOutcome(p, succeeded ? "SUCCEEDED" : "FAILED");
  }

  /**
   * Browser checkout finished: confirm the three values Razorpay handed the browser. The signature
   * binds order id and payment id to the key secret, so a customer cannot claim someone else's
   * payment or invent one.
   */
  public PaymentResponse verifyRazorpay(
      UUID paymentId,
      UUID userId,
      String razorpayOrderId,
      String razorpayPaymentId,
      String signature) {
    Payment p = payments.findById(paymentId).orElseThrow(PaymentService::notFound);
    if (!p.userId().equals(userId)) {
      throw notFound();
    }
    if (!razorpay.enabled()) {
      throw new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Razorpay is not configured");
    }
    if (p.providerOrderId() == null || !p.providerOrderId().equals(razorpayOrderId)) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Payment does not match this order");
    }
    if (!razorpay.verifyCheckoutSignature(razorpayOrderId, razorpayPaymentId, signature)) {
      log.warn("Invalid Razorpay checkout signature for payment {}", p.id());
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Invalid payment signature");
    }
    flagIfSettledElsewhere(p, PaymentProviders.RAZORPAY, razorpayPaymentId);
    payments.setProviderPaymentId(p.id(), razorpayPaymentId);
    return view(applyOutcome(p, "SUCCEEDED"));
  }

  /**
   * Razorpay webhook: verified over the exact raw body, idempotent. Only a captured payment is
   * acted on. A failed attempt is ignored on purpose: Razorpay lets the customer retry on the same
   * order, so failing the payment here could cancel an order the customer is about to pay for. An
   * order that is never paid is cancelled by order-service after its payment window.
   */
  public void handleRazorpayWebhook(String payload, String signature, String eventId) {
    if (!razorpay.enabled()) {
      throw new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Webhook not configured");
    }
    if (!razorpay.verifyWebhookSignature(payload, signature)) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Invalid webhook signature");
    }
    JsonNode root;
    try {
      root = JSON.readTree(payload);
    } catch (Exception e) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Unreadable webhook payload");
    }
    String type = root.path("event").asText("");
    if (type.startsWith("refund.")) {
      handleRazorpayRefund(root, type, eventId);
      return;
    }
    if ("payment.authorized".equals(type)) {
      handleRazorpayAuthorized(root);
      return;
    }
    if (!"payment.captured".equals(type) && !"order.paid".equals(type)) {
      return;
    }
    JsonNode payment = root.path("payload").path("payment").path("entity");
    String paymentId = payment.path("id").asText(null);
    String orderId = payment.path("order_id").asText(null);
    if (orderId == null) {
      orderId = root.path("payload").path("order").path("entity").path("id").asText(null);
    }
    String dedupeKey =
        "rzp:" + (eventId != null && !eventId.isBlank() ? eventId : type + ":" + paymentId);
    if (payments.eventProcessed(dedupeKey)) {
      log.info("Duplicate Razorpay event {} ignored", dedupeKey);
      return;
    }
    if (orderId == null) {
      log.warn("Razorpay event {} carries no order id", dedupeKey);
      return;
    }
    Payment p = payments.findByProviderOrderId(orderId).orElse(null);
    if (p == null) {
      log.warn("Razorpay event {} for unknown order {}", dedupeKey, orderId);
      return;
    }
    if (payment.has("amount")
        && (payment.path("amount").asLong() != toMinor(p.amount())
            || !p.currency().equalsIgnoreCase(payment.path("currency").asText(p.currency())))) {
      // Never mark an order paid on an amount we did not ask for.
      log.error(
          "Razorpay payment {} amount/currency does not match payment {}; not applying",
          paymentId,
          p.id());
      return;
    }
    flagIfSettledElsewhere(p, PaymentProviders.RAZORPAY, paymentId);
    if (paymentId != null) {
      payments.setProviderPaymentId(p.id(), paymentId);
    }
    applyOutcome(p, "SUCCEEDED");
    payments.recordEvent(dedupeKey);
  }

  /**
   * payment.authorized only marks a candidate: {@code authorized_at} is stamped (idempotently, only
   * while the payment is unpaid, and not at all when the entity already says captured). It never
   * alerts: Razorpay documents that the payment "can be in the captured state when the
   * payment.authorized webhook is fired" and that the webhook order is not fixed, so the event
   * alone proves nothing. {@link RazorpayWatch} alerts if the payment is still unpaid after a
   * delay. Source (Q1): https://razorpay.com/docs/webhooks/payloads/payments/ - event {@code
   * payment.authorized}, entity at {@code payload.payment.entity} ({@code order_id}, {@code
   * captured}).
   */
  private void handleRazorpayAuthorized(JsonNode root) {
    JsonNode e = root.path("payload").path("payment").path("entity");
    String orderId = e.path("order_id").asText(null);
    if (orderId == null) {
      log.warn("Razorpay payment.authorized carries no order id; ignored");
    } else if (!e.path("captured").asBoolean(false)) {
      payments.stampAuthorized(orderId);
    }
  }

  /** Idempotent by idempotencyKey. */
  public RefundResponse refund(UUID orderId, RefundRequest req) {
    Payment p = find(orderId);
    if (payments.refundExists(req.idempotencyKey())) {
      return refundView(payments.findById(p.id()).orElse(p), req.idempotencyKey());
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
    String providerRefundId = null;
    boolean toWallet = "WALLET".equalsIgnoreCase(req.destination());
    if (!toWallet && "CARD".equals(p.method())) {
      if (PaymentProviders.STRIPE.equals(p.mode()) && p.intentId() != null) {
        stripeRefundId = stripe.refund(p.intentId(), toMinor(amount), req.idempotencyKey());
      } else if (PaymentProviders.RAZORPAY.equals(p.mode()) && p.providerOrderId() != null) {
        providerRefundId = refundRazorpay(p, amount, req);
      }
    }
    String stripeRefund = stripeRefundId;
    String providerRefund = providerRefundId;
    return tx.execute(
        status -> {
          Payment locked = payments.lockById(p.id()).orElseThrow();
          if (payments.refundExists(req.idempotencyKey())) {
            return refundView(locked, req.idempotencyKey());
          }
          BigDecimal total = locked.refundedAmount().add(amount);
          if (total.compareTo(locked.amount()) > 0) {
            throw new CodedException(
                ErrorCode.DOMAIN_RULE_VIOLATION, "Refund amount exceeds the refundable balance");
          }
          // A Razorpay refund is only accepted, not done: PENDING until a webhook confirms it.
          String insertedStatus = providerRefund != null ? "PENDING" : "SUCCEEDED";
          UUID refundRowId =
              payments.insertRefund(
                  locked.id(),
                  amount,
                  req.reason(),
                  stripeRefund,
                  providerRefund,
                  req.idempotencyKey(),
                  insertedStatus);
          if (toWallet) {
            wallet.credit(
                locked.userId(),
                amount,
                locked.currency(),
                "REFUND",
                locked.orderId().toString(),
                "refund-" + req.idempotencyKey());
          }
          String newStatus =
              total.compareTo(locked.amount()) == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
          payments.applyRefund(locked.id(), total, newStatus);
          if (providerRefund != null
              && applyParkedRefundEvents(locked.id(), refundRowId, providerRefund)) {
            return refundView(payments.findById(locked.id()).orElseThrow(), req.idempotencyKey());
          }
          return new RefundResponse(newStatus, total, insertedStatus);
        });
  }

  /**
   * Razorpay refund webhook (processed / failed); other refund.* events are a 200 no-op. One tx:
   * the event id is recorded, then the payment row and the refund row are locked (payment first, as
   * refund() does). An event whose refund row is not committed yet is parked and applied by
   * refund(); both sides lock the payment row, so one of them always sees the other's row.
   */
  private void handleRazorpayRefund(JsonNode root, String type, String eventId) {
    if (!"refund.processed".equals(type) && !"refund.failed".equals(type)) {
      return;
    }
    JsonNode e = root.path("payload").path("refund").path("entity");
    String refundId = e.path("id").asText(null);
    String paymentId = e.path("payment_id").asText(null);
    String currency = e.path("currency").asText("").toUpperCase(Locale.ROOT);
    if (refundId == null || paymentId == null || !e.has("amount") || currency.length() != 3) {
      log.warn("Razorpay {} event has no usable payload.refund.entity; ignored", type);
      return;
    }
    String target = "refund.processed".equals(type) ? "SUCCEEDED" : "FAILED";
    long minor = e.path("amount").asLong();
    String dedupeKey =
        "rzp:" + (eventId != null && !eventId.isBlank() ? eventId : type + ":" + refundId);
    tx.executeWithoutResult(
        status -> {
          if (!payments.recordEvent(dedupeKey)) {
            log.info("Duplicate Razorpay event {} ignored", dedupeKey);
            return;
          }
          Payment p = payments.findByProviderPaymentIdForUpdate(paymentId).orElse(null);
          if (p == null) {
            log.warn("Razorpay {} for refund {} of unknown payment {}", type, refundId, paymentId);
            return;
          }
          var rows = payments.findRefundsByProviderRefundId(refundId);
          if (rows.size() > 1) {
            log.error(
                "REFUND ID DUPLICATED: {} refund rows share Razorpay refund {} (payment {}); {} not"
                    + " applied, reconcile by hand",
                rows.size(),
                refundId,
                p.id(),
                type);
            return;
          }
          if (rows.isEmpty()) {
            log.warn(
                "Razorpay {} for unknown refund {}; parked until it is recorded", type, refundId);
            payments.parkRefundEvent(refundId, target, paymentId, minor, currency);
            return;
          }
          applyRefundEvent(p, rows.get(0), target, paymentId, minor, currency, false);
        });
  }

  /**
   * Applies events parked before the refund row existed, to the row just inserted. Returns true if
   * any were found. A parked event is gone from the table and its event id is recorded, so one that
   * cannot be applied is logged at ERROR: nothing will redeliver it.
   */
  private boolean applyParkedRefundEvents(
      UUID paymentId, UUID refundRowId, String providerRefundId) {
    var parked = payments.takeParkedRefundEvents(providerRefundId);
    if (parked.isEmpty()) {
      return false;
    }
    Payment p = payments.lockById(paymentId).orElseThrow();
    var r = payments.findRefundByIdForUpdate(refundRowId).orElseThrow();
    for (var e : parked) {
      applyRefundEvent(
          p, r, e.status(), e.providerPaymentId(), e.amountMinor(), e.currency(), true);
    }
    return true;
  }

  private void applyRefundEvent(
      Payment p,
      PaymentRepository.Refund r,
      String target,
      String providerPaymentId,
      long minor,
      String currency,
      boolean wasParked) {
    if (!r.paymentId().equals(p.id())
        || !providerPaymentId.equals(p.providerPaymentId())
        || minor != toMinor(r.amount())
        || !p.currency().equalsIgnoreCase(currency)) {
      String msg =
          "Razorpay refund {} ({} {}) does not match refund row {} of payment {}; not applying";
      if (wasParked) {
        // Dropped for good (its event id is recorded): the refund stays PENDING until reconciled.
        log.error(
            msg + " (PARKED EVENT DROPPED, reconcile by hand)",
            r.providerRefundId(),
            minor,
            currency,
            r.id(),
            p.id());
      } else {
        log.warn(msg, r.providerRefundId(), minor, currency, r.id(), p.id());
      }
      return;
    }
    settleRefund(p, r, target);
  }

  /**
   * PENDING to SUCCEEDED or FAILED, once. SUCCEEDED leaves the payment as it is (the amount was
   * reserved at acceptance). FAILED releases the reserved amount and does not credit the wallet.
   * Caller holds the payment row lock. A terminal state is never overwritten.
   */
  private void settleRefund(Payment p, PaymentRepository.Refund r, String target) {
    if (payments.settleRefundRow(r.id(), target) == 0) {
      String current = payments.refundStatus(r.id());
      if (target.equals(current)) {
        log.info("Refund {} is already {}; duplicate ignored", r.providerRefundId(), current);
      } else if ("SUCCEEDED".equals(current) && "FAILED".equals(target)) {
        // The customer was probably not refunded although we show it as done (for example a refund
        // recorded by an older pod). State is left alone; an operator must reconcile.
        log.error(
            "REFUND FAILED AFTER SUCCEEDED: Razorpay refund {} of {} {} for order {} (refund row"
                + " {}) is recorded SUCCEEDED but Razorpay says it failed; state unchanged,"
                + " reconcile in the Razorpay dashboard",
            r.providerRefundId(),
            r.amount(),
            p.currency(),
            p.orderId(),
            r.id());
      } else {
        log.warn(
            "Refund {} is already {}; conflicting {} ignored",
            r.providerRefundId(),
            current,
            target);
      }
      return;
    }
    if ("FAILED".equals(target)) {
      BigDecimal left = p.refundedAmount().subtract(r.amount());
      if (left.signum() < 0) {
        log.error(
            "LEDGER MISMATCH: releasing refund {} of {} {} for order {} would take refunded_amount"
                + " {} below zero; nothing changed",
            r.providerRefundId(),
            r.amount(),
            p.currency(),
            p.orderId(),
            p.refundedAmount());
        throw new IllegalStateException(
            "Refund release exceeds refunded amount for " + p.orderId());
      }
      payments.applyRefund(p.id(), left, left.signum() == 0 ? "SUCCEEDED" : "PARTIALLY_REFUNDED");
      log.error(
          "REFUND FAILED: Razorpay refund {} of {} {} for order {} (refund row {}) failed; "
              + "balance released, customer NOT refunded",
          r.providerRefundId(),
          r.amount(),
          p.currency(),
          p.orderId(),
          r.id());
    }
  }

  private String refundRazorpay(Payment p, BigDecimal amount, RefundRequest req) {
    String rzpPaymentId = p.providerPaymentId();
    if (rzpPaymentId == null) {
      var captured = razorpay.capturedPayment(p.providerOrderId());
      if (captured == null) {
        throw new CodedException(
            ErrorCode.RESOURCE_CONFLICT, "No captured Razorpay payment to refund");
      }
      rzpPaymentId = captured.id();
      payments.setProviderPaymentId(p.id(), rzpPaymentId);
    }
    return razorpay.refund(rzpPaymentId, toMinor(amount), req.idempotencyKey(), req.reason());
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

  /** The payment's state plus the status of the refund row stored under this key. */
  private RefundResponse refundView(Payment p, String idempotencyKey) {
    return new RefundResponse(
        p.status(), p.refundedAmount(), payments.refundStatusByKey(idempotencyKey));
  }

  private PaymentResponse view(Payment p) {
    String clientSecret = null;
    String publishable = null;
    String providerOrderId = null;
    boolean awaiting = "REQUIRES_PAYMENT".equals(p.status()) || "PROCESSING".equals(p.status());
    if (awaiting
        && PaymentProviders.STRIPE.equals(p.mode())
        && p.intentId() != null
        && stripe.enabled()) {
      try {
        clientSecret = stripe.retrieve(p.intentId()).clientSecret();
        publishable = properties.getStripe().getPublishableKey();
      } catch (RuntimeException e) {
        log.warn("Could not load client secret for payment {}: {}", p.id(), e.getMessage());
      }
    } else if (awaiting
        && PaymentProviders.RAZORPAY.equals(p.mode())
        && p.providerOrderId() != null
        && razorpay.enabled()) {
      providerOrderId = p.providerOrderId();
      publishable = razorpay.keyId();
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
        publishable,
        p.purpose(),
        providerOrderId);
  }
}
