package com.acme.ecommerce.promotion.config;

import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every tunable of promotion-service. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ConfigurationProperties(prefix = "promotion")
public class PromotionProperties {
  @Builder.Default private Jwt jwt = new Jwt();
  @Builder.Default private Coins coins = new Coins();

  /** Shared secret for /internal/** (header X-Internal-Token). */
  @Builder.Default private String internalToken = "";

  /** Loyalty coins. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Coins {
    /** One coin per this many currency units of paid total (INR). */
    @Builder.Default private int earnUnitInr = 100;

    /** Same for every other currency (1 coin per 1 unit, i.e. 1% back at 0.01 per coin). */
    @Builder.Default private int earnUnitOther = 1;

    @Builder.Default private int referralReward = 100;
    @Builder.Default private int expiryDays = 365;

    /** Order events older than this are ignored (the consumer group replays the topic). */
    @Builder.Default private int maxEventAgeDays = 2;
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
