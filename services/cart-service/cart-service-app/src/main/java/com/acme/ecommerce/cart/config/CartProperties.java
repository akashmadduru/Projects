package com.acme.ecommerce.cart.config;

import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable this service has, in one type-safe, IDE-completable place.
 *
 * <p>JavaBean binding, so defaults are field initializers ({@code @DefaultValue} only applies to
 * constructor parameters and record components). Environment overrides live in application.yml.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ConfigurationProperties(prefix = "cart")
public class CartProperties {
  @Builder.Default private Jwt jwt = new Jwt();

  /** Token validation contract with custom helper methods. */
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
