package com.acme.ecommerce.user.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable this service has, in one type-safe, IDE-completable place.
 *
 * <p>JavaBean binding, so defaults are field initializers ({@code @DefaultValue} only applies to
 * constructor parameters and record components). Environment overrides live in application.yml.
 */
@Slf4j
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ConfigurationProperties(prefix = "user")
public class UserProperties {
  @Builder.Default private Jwt jwt = new Jwt();
  @Builder.Default private Referral referral = new Referral();

  @PostConstruct
  void warnOnShortRetention() {
    Referral r = referral;
    if (r != null && r.ipRetention.compareTo(r.ipLookback) < 0) {
      log.warn(
          "user.referral.ip-retention ({}) is shorter than ip-lookback ({}): the effective"
              + " lookback is the retention",
          r.ipRetention,
          r.ipLookback);
    }
  }

  /** Referral claim abuse checks (TD-P8-13). A zero duration disables the matching check. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Referral {
    /** A referral code must be at least this old before it can be claimed. */
    @Builder.Default private Duration minCodeAge = Duration.ofMinutes(10);

    /** A claim is refused when the referrer was seen from the claimant's IP within this window. */
    @Builder.Default private Duration ipLookback = Duration.ofDays(30);

    /** Hashed IPs older than this are deleted by the nightly job. */
    @Builder.Default private Duration ipRetention = Duration.ofDays(30);

    /** HMAC key for IP hashing; blank means a random per-process key (matches only in-process). */
    @ToString.Exclude @Builder.Default private String ipHashSalt = "";
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
