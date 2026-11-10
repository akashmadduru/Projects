package com.acme.ecommerce.auth.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Cookie read/write helpers.
 *
 * <p>Writes go through Spring's {@link ResponseCookie} rather than {@code
 * HttpServletResponse#addCookie}, because the servlet {@link Cookie} API has no {@code SameSite}
 * attribute at all. A refresh cookie without {@code SameSite} is a CSRF-usable credential, so
 * "close enough" is not an option here.
 *
 * <p>Deletion re-sends the cookie with {@code Max-Age=0} AND THE SAME PATH. A browser matches
 * cookies for deletion on name plus path plus domain; a delete sent with the default path silently
 * does nothing and leaves the credential in place - a bug that looks exactly like "logout does not
 * work sometimes".
 */
public final class CookieSupport {

  private CookieSupport() {
    throw new AssertionError("static utility");
  }

  /** Reads a cookie value, if present and non-blank. */
  public static Optional<String> read(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    for (Cookie cookie : cookies) {
      if (name.equals(cookie.getName())) {
        String value = cookie.getValue();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
      }
    }
    return Optional.empty();
  }

  /** Writes an HttpOnly cookie with explicit SameSite and path. */
  public static void write(
      HttpServletResponse response,
      String name,
      String value,
      String path,
      Duration maxAge,
      boolean secure,
      String sameSite,
      String domain) {

    ResponseCookie.ResponseCookieBuilder builder =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(secure)
            .path(path)
            .maxAge(maxAge)
            .sameSite(sameSite);
    if (domain != null && !domain.isBlank()) {
      builder.domain(domain);
    }
    response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
  }

  /** Expires a cookie. The path MUST match the one it was written with. */
  public static void clear(
      HttpServletResponse response,
      String name,
      String path,
      boolean secure,
      String sameSite,
      String domain) {
    write(response, name, "", path, Duration.ZERO, secure, sameSite, domain);
  }
}
