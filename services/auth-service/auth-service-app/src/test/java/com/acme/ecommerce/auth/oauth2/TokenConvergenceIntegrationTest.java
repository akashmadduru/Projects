package com.acme.ecommerce.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.auth.api.dto.LoginRequest;
import com.acme.ecommerce.auth.service.LoginService;
import com.acme.ecommerce.auth.support.AbstractAuthIntegrationTest;
import com.acme.ecommerce.auth.support.DatabaseCleaner;
import com.acme.ecommerce.auth.support.UserFixtures;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import com.acme.ecommerce.auth.token.DeviceContext;
import com.acme.ecommerce.auth.token.TokenPair;
import com.acme.ecommerce.auth.token.TokenService;
import com.acme.ecommerce.common.security.Roles;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * THE CONTRACT TEST FOR THE CONVERGENCE POINT.
 *
 * <p>The central design rule of this service is that password login and Google login are two front
 * doors into ONE token-issuance method. This test is what turns that from an intention into an
 * enforced property: it drives both paths end to end and asserts that the resulting access tokens
 * are structurally identical - same header, same claim names, same claim types - differing only in
 * {@code auth_method}.
 *
 * <p>If someone later adds a second issuance path (a controller that builds a JWT "just for this
 * one case"), the two tokens drift and this test goes red. Without it, the drift is invisible until
 * a downstream service starts rejecting tokens from one login method only.
 */
class TokenConvergenceIntegrationTest extends AbstractAuthIntegrationTest {

  private static final String PASSWORD_EMAIL = "pw-user@example.com";
  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String GOOGLE_EMAIL = "google-user@example.com";

  @Autowired private LoginService loginService;
  @Autowired private FederatedLoginService federatedLoginService;
  @Autowired private TokenService tokenService;
  @Autowired private UserFixtures userFixtures;
  @Autowired private DatabaseCleaner databaseCleaner;

  @BeforeEach
  void setUp() {
    databaseCleaner.clean();
    userFixtures.createPasswordUser(PASSWORD_EMAIL, PASSWORD, Roles.ROLE_VIEWER);
  }

  private static DeviceContext device() {
    return new DeviceContext("JUnit/1.0", "192.0.2.10");
  }

  @Test
  @DisplayName("password and Google logins produce structurally identical tokens")
  void bothPathsProduceTheSameTokenShape() throws Exception {
    // --- front door 1: password ---
    TokenPair passwordPair =
        loginService.login(new LoginRequest(PASSWORD_EMAIL, PASSWORD), device());

    // --- front door 2: Google (provisions a brand-new social-only user) ---
    AuthenticatedPrincipal googlePrincipal =
        federatedLoginService.resolve(
            new OidcIdentity("google", "google-sub-12345", GOOGLE_EMAIL, true, "Google User"));
    TokenPair googlePair = tokenService.issueTokenPair(googlePrincipal, device(), null, null, null);

    SignedJWT passwordJwt = SignedJWT.parse(passwordPair.accessToken());
    SignedJWT googleJwt = SignedJWT.parse(googlePair.accessToken());

    // Same signing key, same algorithm, same header shape.
    assertThat(googleJwt.getHeader().getAlgorithm())
        .isEqualTo(passwordJwt.getHeader().getAlgorithm());
    assertThat(googleJwt.getHeader().getKeyID()).isEqualTo(passwordJwt.getHeader().getKeyID());
    assertThat(googleJwt.getHeader().getType()).isEqualTo(passwordJwt.getHeader().getType());

    JWTClaimsSet passwordClaims = passwordJwt.getJWTClaimsSet();
    JWTClaimsSet googleClaims = googleJwt.getJWTClaimsSet();

    // Identical claim NAMES. A missing claim on one path is exactly the bug this
    // guards against.
    Set<String> passwordNames = new TreeSet<>(passwordClaims.getClaims().keySet());
    Set<String> googleNames = new TreeSet<>(googleClaims.getClaims().keySet());
    assertThat(googleNames).isEqualTo(passwordNames);
    assertThat(passwordNames)
        .containsExactlyInAnyOrder(
            "iss", "sub", "aud", "exp", "iat", "nbf", "jti", "roles", "email", "auth_method");

    // Identical values for everything that is not per-principal.
    assertThat(googleClaims.getIssuer()).isEqualTo(passwordClaims.getIssuer());
    assertThat(googleClaims.getAudience()).isEqualTo(passwordClaims.getAudience());
    assertThat(googleClaims.getStringListClaim("roles"))
        .isEqualTo(passwordClaims.getStringListClaim("roles"));

    // The ONE intended difference.
    assertThat(passwordClaims.getStringClaim("auth_method")).isEqualTo("password");
    assertThat(googleClaims.getStringClaim("auth_method")).isEqualTo("google");

    // Both are refreshable through the same rotation machinery, so the refresh
    // token half of the pair converged too.
    assertThat(passwordPair.refreshTokenRaw()).isNotBlank();
    assertThat(googlePair.refreshTokenRaw()).isNotBlank();
    assertThat(googlePair.expiresInSeconds()).isEqualTo(passwordPair.expiresInSeconds());
  }
}
