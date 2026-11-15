package com.acme.ecommerce.payment.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.payment.config.PaymentProperties;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import jakarta.annotation.PostConstruct;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Thin wrapper over stripe-java so the rest of the service never sees the SDK. */
@Component
public class StripeGateway {

  private static final Logger log = LoggerFactory.getLogger(StripeGateway.class);

  /** Intent snapshot. */
  public record Intent(String id, String clientSecret, String status) {}

  private final PaymentProperties properties;

  public StripeGateway(PaymentProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void init() {
    if (enabled()) {
      Stripe.apiKey = properties.getStripe().getSecretKey();
      log.info("Stripe enabled");
    }
  }

  public boolean enabled() {
    return properties.getStripe().enabled();
  }

  public Intent createIntent(String orderId, long minorAmount, String currency) {
    return createIntent(orderId, minorAmount, currency, "pi-" + orderId);
  }

  /** Explicit idempotency key: a fresh intent after the previous one was cancelled. */
  public Intent createIntent(
      String orderId, long minorAmount, String currency, String idempotencyKey) {
    try {
      PaymentIntentCreateParams params =
          PaymentIntentCreateParams.builder()
              .setAmount(minorAmount)
              .setCurrency(currency.toLowerCase(Locale.ROOT))
              .setAutomaticPaymentMethods(
                  PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                      .setEnabled(true)
                      .build())
              .putMetadata("orderId", orderId)
              .build();
      PaymentIntent pi =
          PaymentIntent.create(
              params, RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
      return new Intent(pi.getId(), pi.getClientSecret(), pi.getStatus());
    } catch (StripeException e) {
      throw upstream(e);
    }
  }

  public Intent retrieve(String intentId) {
    try {
      PaymentIntent pi = PaymentIntent.retrieve(intentId);
      return new Intent(pi.getId(), pi.getClientSecret(), pi.getStatus());
    } catch (StripeException e) {
      throw upstream(e);
    }
  }

  /** Cancels an unpaid intent so a customer who switched provider cannot pay it later. */
  public void cancel(String intentId) {
    try {
      PaymentIntent.retrieve(intentId).cancel();
    } catch (StripeException e) {
      throw upstream(e);
    }
  }

  /** Returns the Stripe refund id. */
  public String refund(String intentId, long minorAmount, String idempotencyKey) {
    try {
      RefundCreateParams params =
          RefundCreateParams.builder().setPaymentIntent(intentId).setAmount(minorAmount).build();
      Refund refund =
          Refund.create(params, RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
      return refund.getId();
    } catch (StripeException e) {
      throw upstream(e);
    }
  }

  private static CodedException upstream(StripeException e) {
    log.warn("Stripe call failed: {}", e.getMessage());
    return new CodedException(ErrorCode.UPSTREAM_UNAVAILABLE, "Payment provider request failed", e);
  }
}
