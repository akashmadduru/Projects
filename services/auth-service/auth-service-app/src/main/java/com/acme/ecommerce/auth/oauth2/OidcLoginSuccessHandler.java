package com.acme.ecommerce.auth.oauth2;

import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.cache.UserSnapshotFactory;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import com.acme.ecommerce.auth.token.DeviceContext;
import com.acme.ecommerce.auth.token.TokenPair;
import com.acme.ecommerce.auth.token.TokenService;
import com.acme.ecommerce.auth.web.RefreshCookieWriter;
import com.acme.ecommerce.auth.web.RequestDeviceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Completes a Google login: front door number two.
 *
 * <p>Calls THE SAME {@link TokenService#issueTokenPair} with {@code familyId = null} that password
 * login calls. It does not build a JWT and does not insert a refresh token - it cannot, both are
 * package-private to the token package. The only difference between a Google session and a password
 * session anywhere in the platform is the {@code auth_method} claim.
 *
 * <p>NO TOKEN GOES INTO THE REDIRECT URL. Not as a query parameter, not in the fragment. A URL is
 * written to browser history, sent as a {@code Referer} to whatever the landing page loads, and
 * captured by every proxy and access log in between - which is why the OAuth2 implicit flow was
 * deprecated. Instead the refresh token is set as an HttpOnly cookie here, and the SPA calls {@code
 * POST /api/auth/refresh} on arrival to obtain its first access token. That costs one extra round
 * trip and removes an entire class of credential leak.
 */
@Component
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

  private static final Logger log = LoggerFactory.getLogger(OidcLoginSuccessHandler.class);

  private final TokenService tokenService;
  private final RefreshCookieWriter refreshCookies;
  private final CookieOAuth2AuthorizationRequestRepository authorizationRequests;
  private final RequestDeviceContext deviceContexts;
  private final AuthMetrics metrics;
  private final UserSnapshotFactory snapshots;

  public OidcLoginSuccessHandler(
      TokenService tokenService,
      RefreshCookieWriter refreshCookies,
      CookieOAuth2AuthorizationRequestRepository authorizationRequests,
      RequestDeviceContext deviceContexts,
      AuthMetrics metrics,
      UserSnapshotFactory snapshots) {
    this.tokenService = tokenService;
    this.refreshCookies = refreshCookies;
    this.authorizationRequests = authorizationRequests;
    this.deviceContexts = deviceContexts;
    this.metrics = metrics;
    this.snapshots = snapshots;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {

    if (!(authentication.getPrincipal() instanceof PlatformOidcUser oidcUser)) {
      // Only reachable if the OidcUserService wiring is replaced without updating
      // this handler. Failing loudly beats issuing a token for a principal whose
      // local identity was never resolved.
      throw new IllegalStateException(
          "Expected a PlatformOidcUser but got "
              + authentication.getPrincipal().getClass().getName());
    }

    DeviceContext device = deviceContexts.from(request);
    AuthenticatedPrincipal principal = oidcUser.principal();
    TokenPair pair =
        tokenService.issueTokenPair(
            principal, device, null, null, snapshots.snapshotFor(principal));

    refreshCookies.write(response, pair);
    metrics.loginAttempt(AuthMethod.GOOGLE, "success");

    String target = authorizationRequests.readRedirectTarget(request);
    authorizationRequests.clearRedirectTarget(response);

    log.info("Federated login succeeded for userId={}", principal.userId());
    response.sendRedirect(target);
  }
}
