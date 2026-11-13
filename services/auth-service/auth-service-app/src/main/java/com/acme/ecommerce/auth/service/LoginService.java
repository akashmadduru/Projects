package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.api.dto.LoginRequest;
import com.acme.ecommerce.auth.cache.UserSnapshotFactory;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.auth.security.AuthUserDetails;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import com.acme.ecommerce.auth.token.DeviceContext;
import com.acme.ecommerce.auth.token.TokenPair;
import com.acme.ecommerce.auth.token.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Password login: front door number one.
 *
 * <p>Ends in exactly the same call every other authentication path ends in - {@link
 * TokenService#issueTokenPair} with {@code familyId = null} for a fresh family. It does not build a
 * JWT, does not touch {@code refresh_tokens}, and could not do either if it wanted to: both are
 * package-private to the token package.
 *
 * <p>NOT {@code @Transactional}. This method is an orchestration of three independent units of work
 * (verify, record the attempt, issue tokens), and each already manages its own boundary. Wrapping
 * them in one transaction would hold a database connection open across an Argon2 verification -
 * ~100ms of pure CPU during which the connection does nothing - and would roll back the failure
 * counter whenever the login failed, which is the one case it exists for.
 *
 * <p>ENUMERATION POSTURE: "no such user" and "wrong password" both produce {@link
 * AuthErrorCode#AUTH_INVALID_CREDENTIALS} with identical text and comparable timing (see {@code
 * DatabaseUserDetailsService}). A locked account does report itself as locked, which is a
 * deliberate usability trade-off: it is reachable only after several failures against a real
 * address, and the alternative is a user who is silently unable to log in with a password they know
 * is correct.
 */
@Service
public class LoginService {

  private static final Logger log = LoggerFactory.getLogger(LoginService.class);

  private final AuthenticationManager authenticationManager;
  private final LoginAttemptService loginAttempts;
  private final TokenService tokenService;
  private final AuthMetrics metrics;
  private final UserSnapshotFactory snapshots;
  private final TwoFactorService twoFactor;

  public LoginService(
      AuthenticationManager authenticationManager,
      LoginAttemptService loginAttempts,
      TokenService tokenService,
      AuthMetrics metrics,
      UserSnapshotFactory snapshots,
      TwoFactorService twoFactor) {
    this.authenticationManager = authenticationManager;
    this.loginAttempts = loginAttempts;
    this.tokenService = tokenService;
    this.metrics = metrics;
    this.snapshots = snapshots;
    this.twoFactor = twoFactor;
  }

  /**
   * Verifies credentials and issues a brand-new token family.
   *
   * @throws AuthException with a deliberately generic code on any failure
   */
  public LoginResult login(LoginRequest request, DeviceContext device) {
    String email = com.acme.ecommerce.auth.domain.UserAccount.normaliseEmail(request.getEmail());

    Authentication authentication;
    try {
      authentication =
          authenticationManager.authenticate(
              new UsernamePasswordAuthenticationToken(email, request.getPassword()));

    } catch (LockedException e) {
      // Checked BEFORE the password is verified, so a locked account costs an
      // attacker nothing to discover but also costs us no Argon2 work to refuse.
      metrics.loginAttempt(AuthMethod.PASSWORD, "locked");
      throw new AuthException(
          AuthErrorCode.AUTH_ACCOUNT_LOCKED, "Login refused: account temporarily locked");

    } catch (DisabledException e) {
      metrics.loginAttempt(AuthMethod.PASSWORD, "disabled");
      throw new AuthException(
          AuthErrorCode.AUTH_ACCOUNT_DISABLED, "Login refused: account disabled");

    } catch (BadCredentialsException e) {
      // Covers BOTH "wrong password" and "no such user" - DaoAuthenticationProvider
      // translates UsernameNotFoundException into this, after running a dummy hash
      // comparison so the two cost the same wall-clock time.
      loginAttempts.recordFailure(email);
      metrics.loginAttempt(AuthMethod.PASSWORD, "bad_credentials");
      // The email is logged; the password never is (LoginRequest#toString masks it).
      log.info("Failed password login for {}", email);
      throw new AuthException(
          AuthErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials for " + email);
    }

    AuthUserDetails principal = (AuthUserDetails) authentication.getPrincipal();
    loginAttempts.recordSuccess(principal.getUserId());
    metrics.loginAttempt(AuthMethod.PASSWORD, "success");

    AuthenticatedPrincipal issuancePrincipal =
        new AuthenticatedPrincipal(
            principal.getUserId(),
            principal.getUsername(),
            principal.getRoleClaimNames(),
            AuthMethod.PASSWORD);

    // THE CONVERGENCE POINT. familyId == null starts a new family.
    return twoFactor.completeLogin(issuancePrincipal, device);
  }
}
