package com.acme.ecommerce.inventory.config;

import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every tunable this service has, in one type-safe, IDE-completable place.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ConfigurationProperties(prefix = "inventory")
public class InventoryProperties {
  @DefaultValue Jwt jwt;

  /**
   * Token validation contract with custom helper methods.
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Jwt {
    @DefaultValue("https://auth.acme-ecommerce.internal") String issuer;
    @DefaultValue("ecommerce-platform") String audience;
    @DefaultValue("AUTH-SERVICE") String jwksServiceId;
    @DefaultValue("/.well-known/jwks.json") String jwksPath;
    @DefaultValue("") String jwksUri;
    @DefaultValue("2s") Duration jwksConnectTimeout;
    @DefaultValue("3s") Duration jwksReadTimeout;

    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    public boolean hasStaticJwksUri() {
      return jwksUri != null && !jwksUri.isBlank();
    }

    public String effectiveJwksUri() {
      return hasStaticJwksUri() ? jwksUri : "lb://" + jwksServiceId + jwksPath;
    }
  }
}
