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
