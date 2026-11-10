package com.acme.ecommerce.gateway.config;

import java.time.Duration;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The gateway's own settings, bound from gateway.* configuration.
 * Holds platform decisions for CORS, JWT, and JWKS caching.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {
  Jwt jwt;
  Jwks jwks;
  Cors cors;

  /**
   * Token validation contract with custom helper methods.
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Jwt {
    @DefaultValue("${AUTH_JWT_ISSUER:https://auth.acme-ecommerce.internal}") String issuer;
    @DefaultValue("${AUTH_JWT_AUDIENCES:ecommerce-platform}") String audience;
    @DefaultValue("AUTH-SERVICE") String jwksServiceId;
    @DefaultValue("/.well-known/jwks.json") String jwksPath;
    @DefaultValue("") String jwksUri;

    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    public boolean hasStaticJwksUri() {
      return jwksUri != null && !jwksUri.isBlank();
    }
  }

  /**
   * JWKS cache policy with intelligent refresh and outage tolerance.
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Jwks {
    @DefaultValue("15m") Duration ttl;
    @DefaultValue("5s") Duration refreshTimeout;
    @DefaultValue("2m") Duration refreshAhead;
    @DefaultValue("30s") Duration rateLimitInterval;
    @DefaultValue("1h") Duration outageTtl;
    @DefaultValue("2s") Duration connectTimeout;
    @DefaultValue("3s") Duration readTimeout;
  }

  /**
   * Browser origin policy for cross-origin requests.
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Cors {
    @DefaultValue("${GATEWAY_CORS_ALLOWED_ORIGINS:http://localhost:5173}")
    List<String> allowedOrigins;
    @DefaultValue({"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    List<String> allowedMethods;
    @DefaultValue({"Authorization", "Content-Type", "X-Correlation-Id"})
    List<String> allowedHeaders;
    @DefaultValue({"X-Correlation-Id"})
    List<String> exposedHeaders;
    @DefaultValue("1h") Duration maxAge;
  }
}
