package com.acme.ecommerce.auth.oauth2;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Sends a failed federated login back to the SPA with a machine-readable reason.
 *
 * <p>The default handler renders Spring Security's HTML error page, which is useless to a SPA and
 * leaks framework details. Redirecting with {@code ?error=<code>} lets the front end show a real
 * message ("this Google account's email address is not verified" is actionable; "authentication
 * failed" is not).
 *
 * <p>ONLY THE CODE IS FORWARDED - never the exception message, which can contain the provider's raw
 * error description, a subject identifier or a URL with query parameters. The code comes from the
 * closed {@link AuthErrorCode} enum, so nothing attacker-controlled reaches the redirect, and it is
 * URL-encoded regardless.
 *
 * <p>The redirect target itself comes from the same allowlist the success path uses; a failure must
 * not become the easier open redirect.
 */
@Component
public class OidcLoginFailureHandler implements AuthenticationFailureHandler {

  private static final Logger log = LoggerFactory.getLogger(OidcLoginFailureHandler.class);

  private final CookieOAuth2AuthorizationRequestRepository authorizationRequests;
  private final AuthMetrics metrics;

  public OidcLoginFailureHandler(
      CookieOAuth2AuthorizationRequestRepository authorizationRequests, AuthMetrics metrics) {
    this.authorizationRequests = authorizationRequests;
    this.metrics = metrics;
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {

    AuthErrorCode code = codeOf(exception);
    metrics.loginAttempt(AuthMethod.GOOGLE, "failure");
    log.warn("Federated login failed [{}]: {}", code.code(), exception.getMessage());

    String target = authorizationRequests.readRedirectTarget(request);
    authorizationRequests.clearRedirectTarget(response);

    String separator = target.contains("?") ? "&" : "?";
    response.sendRedirect(
        target + separator + "error=" + URLEncoder.encode(code.code(), StandardCharsets.UTF_8));
  }

  /**
   * Recovers the specific auth code that {@code PlatformOidcUserService} smuggled through Spring
   * Security's exception type, falling back to a generic failure for anything the framework itself
   * raised (bad state, missing authorization request, provider error).
   */
  private static AuthErrorCode codeOf(AuthenticationException exception) {
    if (exception instanceof OAuth2AuthenticationException oauth2Exception) {
      String errorCode = oauth2Exception.getError().getErrorCode();
      for (AuthErrorCode candidate : AuthErrorCode.values()) {
        if (candidate.code().equals(errorCode)) {
          return candidate;
        }
      }
    }
    return AuthErrorCode.AUTH_OIDC_LOGIN_FAILED;
  }
}
