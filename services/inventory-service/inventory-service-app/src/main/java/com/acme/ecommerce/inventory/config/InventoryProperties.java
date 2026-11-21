package com.acme.ecommerce.inventory.config;

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
@ConfigurationProperties(prefix = "inventory")
public class InventoryProperties {
  @Builder.Default private Jwt jwt = new Jwt();

  @Builder.Default private Internal internal = new Internal();

  @Builder.Default private Stock stock = new Stock();

  /** How stock behaves for products and variants (TD-P6-04, TD-P7-07). */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Stock {
    /**
     * A platform order line with no stock rows at all. REJECT (default): it is out of stock.
     * UNLIMITED: the pre-2026-11-17 behaviour, kept only as an escape hatch.
     */
    @Builder.Default private MissingRows missingRows = MissingRows.REJECT;

    /**
     * Quantity given to a stock row created for a product or variant that had none (the catalog
     * sync, {@code POST /internal/inventory/stock/ensure}). 0 in production, so nothing becomes
     * sellable until someone stocks it; the local stack sets a demo value.
     */
    @Builder.Default private int defaultQuantity = 0;
  }

  public enum MissingRows {
    REJECT,
    UNLIMITED
  }

  /** Shared secret for /internal/** callers (INTERNAL_API_TOKEN). */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Internal {
    @Builder.Default private String token = "";
  }

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
