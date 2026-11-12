package com.acme.ecommerce.order.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
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
@ConfigurationProperties(prefix = "order")
public class OrderProperties {
  @Builder.Default private Jwt jwt = new Jwt();

  @Builder.Default private Internal internal = new Internal();

  @Builder.Default private Shipping shipping = new Shipping();

  @Builder.Default private Tax tax = new Tax();

  @Builder.Default private Delivery delivery = new Delivery();

  @Builder.Default private Returns returns = new Returns();

  @Builder.Default private Pending pending = new Pending();

  @Builder.Default private Seller seller = new Seller();

  @Builder.Default private Loyalty loyalty = new Loyalty();

  /** Coins and subscription rules. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Loyalty {
    /** Max share of the subtotal payable with coins. */
    @Builder.Default private int coinMaxPercent = 20;

    /** Subscribe & Save line discount. */
    @Builder.Default private int subscriptionDiscountPercent = 5;

    @Builder.Default private int subscriptionMaxFailures = 3;
  }

  /** Shared secret for /internal/** (inbound) and the internal Feign clients (outbound). */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Internal {
    @Builder.Default private String token = "";
  }

  /** Shipping fee rules per currency family. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Shipping {
    @Builder.Default private BigDecimal inrFreeThreshold = new BigDecimal("499");
    @Builder.Default private BigDecimal inrFee = new BigDecimal("40");
    @Builder.Default private BigDecimal otherFreeThreshold = new BigDecimal("25");
    @Builder.Default private BigDecimal otherFee = new BigDecimal("4.99");
  }

  /** GST (INR only, inclusive). */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Tax {
    @Builder.Default private BigDecimal gstPercent = new BigDecimal("18");

    /** State code and name(s) that count as the warehouse state (intra-state = CGST+SGST). */
    @Builder.Default private List<String> warehouseStateAliases = List.of("KA", "Karnataka");
  }

  /** Delivery estimate and COD rules. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Delivery {
    @Builder.Default private String warehousePincodePrefix = "560";
    @Builder.Default private BigDecimal codLimitInr = new BigDecimal("50000");
    @Builder.Default private BigDecimal codLimitOther = new BigDecimal("1000");
    @Builder.Default private String zone = "Asia/Kolkata";
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Returns {
    @Builder.Default private int windowDays = 7;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Pending {
    @Builder.Default private Duration expiry = Duration.ofMinutes(30);
  }

  /** Seller block printed on invoices. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Seller {
    @Builder.Default private String name = "Acme Commerce Pvt Ltd";
    @Builder.Default private String address = "1 MG Road, Bengaluru, Karnataka 560001, India";
    @Builder.Default private String gstin = "29ABCDE1234F1Z5";
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
