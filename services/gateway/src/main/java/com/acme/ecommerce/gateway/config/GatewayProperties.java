package com.acme.ecommerce.gateway.config;

import java.time.Duration;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The gateway's own settings, bound from gateway.* configuration. Holds platform decisions for
 * CORS, JWT, and JWKS caching.
 *
 * <p>JavaBean binding, so defaults are field initializers ({@code @DefaultValue} only applies to
 * constructor parameters and record components). Environment overrides live in application.yml.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {
  @Builder.Default private Jwt jwt = new Jwt();
  @Builder.Default private Jwks jwks = new Jwks();
  @Builder.Default private Cors cors = new Cors();

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

    public boolean hasStaticJwksUri() {
      return jwksUri != null && !jwksUri.isBlank();
    }
  }

  /** JWKS cache policy with intelligent refresh and outage tolerance. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Jwks {
    @Builder.Default private Duration ttl = Duration.ofMinutes(15);
    @Builder.Default private Duration refreshTimeout = Duration.ofSeconds(5);
    @Builder.Default private Duration refreshAhead = Duration.ofMinutes(2);
    @Builder.Default private Duration rateLimitInterval = Duration.ofSeconds(30);
    @Builder.Default private Duration outageTtl = Duration.ofHours(1);
    @Builder.Default private Duration connectTimeout = Duration.ofSeconds(2);
    @Builder.Default private Duration readTimeout = Duration.ofSeconds(3);
  }

  /** Browser origin policy for cross-origin requests. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Cors {
    @Builder.Default private List<String> allowedOrigins = List.of("http://localhost:5173");

    @Builder.Default
    private List<String> allowedMethods =
        List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    @Builder.Default
    private List<String> allowedHeaders =
        List.of("Authorization", "Content-Type", "X-Correlation-Id");

    @Builder.Default private List<String> exposedHeaders = List.of("X-Correlation-Id");
    @Builder.Default private Duration maxAge = Duration.ofHours(1);
  }
}
