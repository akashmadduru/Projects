package com.acme.ecommerce.auth.web;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.token.TokenPair;
import com.acme.ecommerce.common.core.time.TimeProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The one place the refresh cookie is written, read and cleared.
 *
 * <p>THE ATTRIBUTES ARE THE SECURITY CONTROL, not a formality:
 *
 * <ul>
 *   <li><b>HttpOnly</b> - JavaScript cannot read it, so an XSS that can steal the access token
 *       (10-minute lifetime) still cannot steal the refresh token (14 days). This is the entire
 *       reason the refresh token is never returned in a JSON body.
 *   <li><b>Secure</b> - never sent over plain HTTP. Configurable only so that {@code localhost}
 *       development works; it must be true everywhere else.
 *   <li><b>SameSite=Lax</b> - not sent on cross-site POSTs, which is what CSRF needs. Not {@code
 *       Strict}, because the Google login flow returns via a cross-site redirect and Strict would
 *       withhold the cookie on that navigation, breaking OIDC login entirely.
 *   <li><b>Path=/api/auth</b> - the browser attaches it only to auth endpoints, so it is not
 *       broadcast on every product API call the SPA makes.
 * </ul>
 *
 * <p>The cookie's {@code Max-Age} tracks the token's own expiry rather than being a fixed 14 days:
 * a rotated child inherits the family's absolute cap, so near the end of a family's life the cookie
 * and the token die together instead of the browser holding a credential the server already
 * rejects.
 */
@Component
public class RefreshCookieWriter {

  private final AuthProperties.Cookie config;
  private final TimeProvider timeProvider;

  public RefreshCookieWriter(AuthProperties properties, TimeProvider timeProvider) {
    this.config = properties.cookie();
    this.timeProvider = timeProvider;
  }

  /** The cookie name, for tests and for documentation. */
  public String cookieName() {
    return config.name();
  }

  /** Reads the raw refresh token. The ONLY place a refresh token may be read from. */
  public Optional<String> read(HttpServletRequest request) {
    return CookieSupport.read(request, config.name());
  }

  /** Writes (or rotates) the refresh cookie. */
  public void write(HttpServletResponse response, TokenPair pair) {
    Duration maxAge = Duration.between(timeProvider.now(), pair.refreshExpiresAt());
    if (maxAge.isNegative()) {
      maxAge = Duration.ZERO;
    }
    CookieSupport.write(
        response,
        config.name(),
        pair.refreshTokenRaw(),
        config.path(),
        maxAge,
        config.secure(),
        config.sameSite(),
        config.domain());
  }

  /** Expires the refresh cookie. Used by logout and by reuse detection. */
  public void clear(HttpServletResponse response) {
    CookieSupport.clear(
        response,
        config.name(),
        config.path(),
        config.secure(),
        config.sameSite(),
        config.domain());
  }
}
