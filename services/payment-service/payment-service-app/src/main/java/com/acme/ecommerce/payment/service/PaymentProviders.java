package com.acme.ecommerce.payment.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.payment.config.PaymentProperties;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Which online payment providers are switched on, and which one a new payment starts with. Several
 * can be on at once; the customer picks at checkout. With none configured the service runs in
 * SIMULATED mode, which is refused under the {@code prod} profile.
 */
@Component
public class PaymentProviders {

  public static final String STRIPE = "STRIPE";
  public static final String RAZORPAY = "RAZORPAY";
  public static final String SIMULATED = "SIMULATED";

  private static final Logger log = LoggerFactory.getLogger(PaymentProviders.class);

  private final PaymentProperties properties;
  private final Environment environment;

  public PaymentProviders(PaymentProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
  }

  @PostConstruct
  void init() {
    List<String> on = enabled();
    if (!on.isEmpty()) {
      log.info("payment-service providers enabled: {} (default {})", on, defaultProvider());
    } else if (environment.acceptsProfiles(Profiles.of("prod"))) {
      // SIMULATED mode lets a customer mark their own card payment as paid through
      // POST /api/payments/{id}/simulate. That is a dev convenience and must be impossible to
      // reach in production, so a prod deployment with no real provider refuses to start at all.
      throw new IllegalStateException(
          "At least one of STRIPE_SECRET_KEY or RAZORPAY_KEY_ID + RAZORPAY_KEY_SECRET is required "
              + "when the 'prod' profile is active; refusing to start payment-service in "
              + "SIMULATED mode");
    } else {
      log.warn("No payment provider configured: payment-service running in SIMULATED mode");
    }
  }

  /** Enabled provider ids in a stable order: STRIPE, RAZORPAY. */
  public List<String> enabled() {
    List<String> on = new ArrayList<>(2);
    if (properties.getStripe().enabled()) {
      on.add(STRIPE);
    }
    if (properties.getRazorpay().enabled()) {
      on.add(RAZORPAY);
    }
    return on;
  }

  public boolean anyEnabled() {
    return !enabled().isEmpty();
  }

  public boolean isEnabled(String provider) {
    return provider != null && enabled().contains(provider.toUpperCase(Locale.ROOT));
  }

  /** The configured default if it is enabled, else the first enabled provider, else SIMULATED. */
  public String defaultProvider() {
    List<String> on = enabled();
    String wanted = properties.getDefaultProvider();
    if (wanted != null && on.contains(wanted.trim().toUpperCase(Locale.ROOT))) {
      return wanted.trim().toUpperCase(Locale.ROOT);
    }
    return on.isEmpty() ? SIMULATED : on.get(0);
  }

  /** Resolves a requested provider, or the default when none was asked for. */
  public String resolve(String requested) {
    if (requested == null || requested.isBlank()) {
      return defaultProvider();
    }
    String provider = requested.trim().toUpperCase(Locale.ROOT);
    if (!enabled().contains(provider)) {
      throw new CodedException(
          ErrorCode.VALIDATION_FAILED, "Payment provider " + provider + " is not available");
    }
    return provider;
  }
}
