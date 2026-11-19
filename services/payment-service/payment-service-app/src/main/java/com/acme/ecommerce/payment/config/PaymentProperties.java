package com.acme.ecommerce.payment.config;

import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every tunable of payment-service. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {
  @Builder.Default private Jwt jwt = new Jwt();
  @Builder.Default private Stripe stripe = new Stripe();
  @Builder.Default private Razorpay razorpay = new Razorpay();

  /**
   * Provider preselected at checkout (STRIPE or RAZORPAY) when more than one is configured. Blank
   * means the first configured one.
   */
  @Builder.Default private String defaultProvider = "";

  /** Shared secret for /internal/** (header X-Internal-Token). */
  @Builder.Default private String internalToken = "";

  /** Stripe keys. A blank secret key means SIMULATED mode. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Stripe {
    @Builder.Default private String secretKey = "";
    @Builder.Default private String publishableKey = "";
    @Builder.Default private String webhookSecret = "";

    public boolean enabled() {
      return secretKey != null && !secretKey.isBlank();
    }
  }

  /**
   * Razorpay keys. A blank key id or key secret disables Razorpay. Orders are INR only unless the
   * Razorpay account has international payments enabled.
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Razorpay {
    @Builder.Default private String keyId = "";
    @Builder.Default private String keySecret = "";
    @Builder.Default private String webhookSecret = "";
    @Builder.Default private String baseUrl = "https://api.razorpay.com";
    @Builder.Default private Duration connectTimeout = Duration.ofSeconds(3);
    @Builder.Default private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * How long a caller that is creating the Razorpay order owns the right to do so. Must exceed
     * connect + read timeout; after it another caller may take over.
     */
    @Builder.Default private Duration provisionLease = Duration.ofSeconds(18);

    /** Period of the capture-mode and stuck-refund watch. */
    @Builder.Default private long watchIntervalMs = 300_000;

    /** A payment authorized but still unpaid this long after payment.authorized is alerted. */
    @Builder.Default private Duration captureAlertAfter = Duration.ofMinutes(15);

    /** A refund still PENDING this long after it was accepted is alerted. */
    @Builder.Default private Duration refundPendingAlertAfter = Duration.ofDays(10);

    public boolean enabled() {
      return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
    }
  }

  /** Token validation contract. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Jwt {
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    @Builder.Default private String issuer = "https://auth.acme-ecommerce.internal";
    @Builder.Default private String audience = "ecommerce-platform";
    @Builder.Default private String jwksServiceId = "AUTH-SERVICE";
    @Builder.Default private String jwksPath = "/.well-known/jwks.json";
    @Builder.Default private String jwksUri = "";
    @Builder.Default private Duration jwksConnectTimeout = Duration.ofSeconds(2);
    @Builder.Default private Duration jwksReadTimeout = Duration.ofSeconds(3);

    public boolean hasStaticJwksUri() {
      return jwksUri != null && !jwksUri.isBlank();
    }

    public String effectiveJwksUri() {
      return hasStaticJwksUri() ? jwksUri : "lb://" + jwksServiceId + jwksPath;
    }
  }
}
