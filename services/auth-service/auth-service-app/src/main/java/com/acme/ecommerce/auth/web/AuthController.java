package com.acme.ecommerce.auth.web;

import com.acme.ecommerce.auth.api.AuthClient;
import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.api.dto.LoginRequest;
import com.acme.ecommerce.auth.api.dto.RegisterRequest;
import com.acme.ecommerce.auth.api.dto.TokenResponse;
import com.acme.ecommerce.auth.api.dto.UserResponse;
import com.acme.ecommerce.auth.api.dto.ValidateRequest;
import com.acme.ecommerce.auth.api.dto.ValidateResponse;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.service.LoginService;
import com.acme.ecommerce.auth.service.RegistrationService;
import com.acme.ecommerce.auth.service.UserPrincipalService;
import com.acme.ecommerce.auth.token.RefreshTokenService;
import com.acme.ecommerce.auth.token.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP edge of auth-service.
 *
 * <p>Deliberately thin: bind, delegate, translate to a response. There is no branching on
 * credentials, no token construction and no persistence here. Everything security-relevant lives in
 * a service that is unit-testable without a servlet container, which is what makes it possible to
 * prove the rules rather than exercise them.
 *
 * <p>Errors are never caught here. {@link AuthException} propagates to {@code AuthProblemHandler},
 * which is the single place an exception becomes a response body.
 */
@RestController
@RequestMapping(AuthClient.BASE_PATH)
public class AuthController {

  private final RegistrationService registrationService;
  private final LoginService loginService;
  private final RefreshTokenService refreshTokenService;
  private final UserPrincipalService principals;
  private final RefreshCookieWriter refreshCookies;
  private final RequestDeviceContext deviceContexts;

  public AuthController(
      RegistrationService registrationService,
      LoginService loginService,
      RefreshTokenService refreshTokenService,
      UserPrincipalService principals,
      RefreshCookieWriter refreshCookies,
      RequestDeviceContext deviceContexts) {
    this.registrationService = registrationService;
    this.loginService = loginService;
    this.refreshTokenService = refreshTokenService;
    this.principals = principals;
    this.refreshCookies = refreshCookies;
    this.deviceContexts = deviceContexts;
  }

  /**
   * Self-service sign-up. Gated by {@code auth.registration.enabled}, which is false by default.
   *
   * <p>Returns the created user but NOT a token: registration is not authentication. The client
   * logs in afterwards, which keeps the credential-verification path the only way to obtain a
   * session.
   */
  @PostMapping("/register")
  public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.register(request));
  }

  /** Password login. Issues a new token family and sets the refresh cookie. */
  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {

    TokenPair pair = loginService.login(request, deviceContexts.from(httpRequest));
    refreshCookies.write(httpResponse, pair);
    return ResponseEntity.ok(
        TokenResponse.bearer(pair.accessToken(), pair.expiresInSeconds(), AuthMethod.PASSWORD));
  }

  /**
   * Rotates the refresh token and issues a fresh access token.
   *
   * <p>THE TOKEN IS READ ONLY FROM THE COOKIE. There is deliberately no {@code Authorization}
   * header fallback and no request-body field: accepting either would mean JavaScript could supply
   * the refresh token, which is the exact capability {@code HttpOnly} exists to deny. An endpoint
   * that accepts both is no safer than one that accepts only the header - an XSS simply uses the
   * path that works.
   *
   * <p>The cookie is cleared on every rejection EXCEPT {@link AuthErrorCode#AUTH_REFRESH_RACE},
   * where the client's cookie is (or is about to be) the winner's valid child and clearing it would
   * log out a user who did nothing wrong.
   */
  @PostMapping("/refresh")
  public ResponseEntity<TokenResponse> refresh(
      HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

    String rawToken = refreshCookies.read(httpRequest).orElse(null);
    TokenPair pair;
    try {
      pair = refreshTokenService.rotate(rawToken, deviceContexts.from(httpRequest));
    } catch (AuthException e) {
      if (e.authErrorCode() != AuthErrorCode.AUTH_REFRESH_RACE) {
        refreshCookies.clear(httpResponse);
      }
      throw e;
    }

    refreshCookies.write(httpResponse, pair);
    return ResponseEntity.ok(
        TokenResponse.bearer(
            pair.accessToken(),
            pair.expiresInSeconds(),
            // The session's original method survives rotation, so a session that
            // began with Google never silently reports itself as a password login.
            AuthMethod.fromClaimValue(claimOf(pair))));
  }

  /**
   * Ends the session, revoking the entire token family.
   *
   * <p>ALWAYS 204, including for a missing, unknown or already-revoked cookie. A logout that can
   * fail is worse than one that no-ops: the user's intent is "end this session", and returning an
   * error leaves them looking at a failure message while their credential is still in the browser.
   * The cookie is cleared unconditionally.
   */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
    refreshTokenService.logout(refreshCookies.read(httpRequest).orElse(null));
    refreshCookies.clear(httpResponse);
    return ResponseEntity.noContent().build();
  }

  /**
   * The caller's own user record.
   *
   * <p>Reads the id from the validated token's {@code sub} claim - never from a request parameter.
   * A {@code /me?userId=...} endpoint is an authorization bypass with extra steps.
   */
  @GetMapping("/me")
  public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
    return principals.currentUser(UUID.fromString(jwt.getSubject()));
  }

  /**
   * Introspection for consumers that cannot validate JWTs locally.
   *
   * <p>Reaching this method already proves the token is valid - the resource-server filter chain
   * verified its signature, issuer, audience and expiry before dispatch. The response therefore
   * just projects the claims into a stable shape.
   *
   * <p>NOT ON THE GATEWAY'S HOT PATH, by design. The gateway validates locally against JWKS; making
   * it call here would put auth-service on the critical path of every single request in the
   * platform and turn one service's bad afternoon into a total outage.
   */
  @PostMapping("/validate")
  public ValidateResponse validate(
      @RequestBody(required = false) ValidateRequest request, @AuthenticationPrincipal Jwt jwt) {

    List<String> roles = jwt.getClaimAsStringList("roles");
    Instant expiresAt = jwt.getExpiresAt();
    return new ValidateResponse(
        true,
        UUID.fromString(jwt.getSubject()),
        jwt.getClaimAsString("email"),
        roles == null ? List.of() : roles,
        AuthMethod.fromClaimValue(jwt.getClaimAsString("auth_method")),
        expiresAt);
  }

  /**
   * Reads the {@code auth_method} back out of the freshly-minted access token.
   *
   * <p>The alternative would be widening {@code TokenPair} with a field only this response needs.
   * Parsing the payload segment is cheap (no signature check - we just signed it) and keeps the
   * token package's contract narrow.
   */
  private static String claimOf(TokenPair pair) {
    try {
      return com.nimbusds.jwt.SignedJWT.parse(pair.accessToken())
          .getJWTClaimsSet()
          .getStringClaim("auth_method");
    } catch (java.text.ParseException e) {
      throw new IllegalStateException("Just-issued access token is unparseable", e);
    }
  }
}
