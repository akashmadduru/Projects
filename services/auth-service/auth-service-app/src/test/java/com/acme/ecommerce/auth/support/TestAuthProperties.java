package com.acme.ecommerce.auth.support;

import com.acme.ecommerce.auth.config.AuthProperties;
import java.time.Duration;
import java.util.List;

/** Builds {@link AuthProperties} for plain unit tests that do not start a Spring context. */
public final class TestAuthProperties {

  private TestAuthProperties() {}

  public static AuthProperties with(AuthProperties.RateLimit rateLimit) {
    return new AuthProperties(
        new AuthProperties.Jwt(
            "https://auth.test.internal",
            List.of("ecommerce-platform"),
            Duration.ofMinutes(10),
            "irrelevant.p12",
            "irrelevant",
            ""),
        new AuthProperties.Refresh(
            Duration.ofDays(14), Duration.ofDays(30), Duration.ofSeconds(10)),
        new AuthProperties.Cookie("refresh_token", "/api/auth", true, "Lax", ""),
        new AuthProperties.Registration(false),
        new AuthProperties.Lockout(5, Duration.ofMinutes(1), Duration.ofMinutes(15)),
        rateLimit,
        new AuthProperties.Oauth2("", "", List.of(), "", ""),
        new AuthProperties.Housekeeping(true, Duration.ofDays(30), Duration.ofHours(1)),
        new AuthProperties.Storefront("http://localhost:3000"));
  }

  public static AuthProperties.RateLimit rateLimit(int loginCapacity, int maxTracked) {
    return new AuthProperties.RateLimit(
        true, loginCapacity, 60, Duration.ofMinutes(1), maxTracked, 3, Duration.ofMinutes(15), 10);
  }
}
