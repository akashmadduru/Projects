package com.acme.ecommerce.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.LoginRequest;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.repository.UserCredentialRepository;
import com.acme.ecommerce.auth.support.AbstractAuthIntegrationTest;
import com.acme.ecommerce.auth.support.DatabaseCleaner;
import com.acme.ecommerce.auth.support.UserFixtures;
import com.acme.ecommerce.auth.token.DeviceContext;
import com.acme.ecommerce.auth.token.TokenPair;
import com.acme.ecommerce.common.security.Roles;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Password authentication, including the enumeration and brute-force defences. */
class PasswordLoginIntegrationTest extends AbstractAuthIntegrationTest {

  private static final String EMAIL = "login@example.com";
  private static final String PASSWORD = "correct-horse-battery-staple";

  @Autowired private LoginService loginService;
  @Autowired private UserCredentialRepository credentials;
  @Autowired private UserFixtures userFixtures;
  @Autowired private DatabaseCleaner databaseCleaner;

  private UserAccount user;

  @BeforeEach
  void setUp() {
    databaseCleaner.clean();
    user = userFixtures.createPasswordUser(EMAIL, PASSWORD, Roles.ROLE_CATALOG_MANAGER);
  }

  private static DeviceContext device() {
    return new DeviceContext("JUnit/1.0", "198.51.100.4");
  }

  @Test
  @DisplayName("correct credentials issue a structurally valid token pair")
  void correctCredentialsIssueTokens() throws Exception {
    TokenPair pair = loginService.login(new LoginRequest(EMAIL, PASSWORD), device());

    assertThat(pair.refreshTokenRaw()).isNotBlank();
    assertThat(pair.expiresInSeconds()).isEqualTo(600);

    SignedJWT jwt = SignedJWT.parse(pair.accessToken());
    assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
    assertThat(jwt.getHeader().getKeyID()).isNotBlank();
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(user.getId().toString());
    assertThat(jwt.getJWTClaimsSet().getStringClaim("email")).isEqualTo(EMAIL);
    assertThat(jwt.getJWTClaimsSet().getStringClaim("auth_method")).isEqualTo("password");
    // UNPREFIXED in the claim; the database stores ROLE_CATALOG_MANAGER.
    assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles"))
        .containsExactly("CATALOG_MANAGER");
  }

  @Test
  @DisplayName("the email is matched case-insensitively")
  void emailIsCaseInsensitive() {
    assertThat(loginService.login(new LoginRequest("LOGIN@Example.COM", PASSWORD), device()))
        .isNotNull();
  }

  @Test
  @DisplayName("a wrong password increments failed_attempts and eventually locks the account")
  void wrongPasswordIncrementsAndLocks() {
    for (int attempt = 1; attempt <= 4; attempt++) {
      assertThatThrownBy(() -> loginService.login(new LoginRequest(EMAIL, "wrong"), device()))
          .isInstanceOf(AuthException.class)
          .extracting(e -> ((AuthException) e).authErrorCode())
          .isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
      assertThat(credentials.findById(user.getId()).orElseThrow().getFailedAttempts())
          .isEqualTo((short) attempt);
    }

    // The 5th failure crosses auth.lockout.max-failed-attempts.
    assertThatThrownBy(() -> loginService.login(new LoginRequest(EMAIL, "wrong"), device()))
        .isInstanceOf(AuthException.class);
    assertThat(credentials.findById(user.getId()).orElseThrow().getLockedUntil()).isNotNull();

    // From here the CORRECT password is refused too, and with a different code -
    // otherwise a locked-out user has no way to understand what happened.
    assertThatThrownBy(() -> loginService.login(new LoginRequest(EMAIL, PASSWORD), device()))
        .isInstanceOf(AuthException.class)
        .extracting(e -> ((AuthException) e).authErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_ACCOUNT_LOCKED);
  }

  @Test
  @DisplayName("a successful login clears the failure counters")
  void successResetsCounters() {
    assertThatThrownBy(() -> loginService.login(new LoginRequest(EMAIL, "wrong"), device()))
        .isInstanceOf(AuthException.class);
    assertThat(credentials.findById(user.getId()).orElseThrow().getFailedAttempts())
        .isEqualTo((short) 1);

    loginService.login(new LoginRequest(EMAIL, PASSWORD), device());

    assertThat(credentials.findById(user.getId()).orElseThrow().getFailedAttempts()).isZero();
  }

  @Test
  @DisplayName("an unknown user produces the SAME error as a wrong password")
  void unknownUserIsIndistinguishableFromWrongPassword() {
    AuthErrorCode unknownUser =
        codeOf(
            () -> loginService.login(new LoginRequest("nobody@example.com", PASSWORD), device()));
    AuthErrorCode wrongPassword =
        codeOf(() -> loginService.login(new LoginRequest(EMAIL, "wrong"), device()));

    // Any difference here - status, code, or message - turns the login endpoint
    // into a user directory.
    assertThat(unknownUser)
        .isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS)
        .isEqualTo(wrongPassword);
  }

  @Test
  @DisplayName("a social-only account cannot be password-authenticated, and looks unknown")
  void socialOnlyAccountLooksUnknown() {
    userFixtures.createSocialOnlyUser("social@example.com", Roles.ROLE_VIEWER);

    // "This address exists but signed up with Google" would be a free oracle for
    // mapping the organisation, so it must be indistinguishable from "no account".
    assertThat(
            codeOf(
                () ->
                    loginService.login(new LoginRequest("social@example.com", PASSWORD), device())))
        .isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
  }

  @Test
  @DisplayName("a disabled account is refused with its own code")
  void disabledAccountIsRefused() {
    userFixtures.disable(user.getId());

    assertThat(codeOf(() -> loginService.login(new LoginRequest(EMAIL, PASSWORD), device())))
        .isEqualTo(AuthErrorCode.AUTH_ACCOUNT_DISABLED);
  }

  private static AuthErrorCode codeOf(Runnable action) {
    try {
      action.run();
      throw new AssertionError("Expected an AuthException");
    } catch (AuthException e) {
      return e.authErrorCode();
    }
  }
}
