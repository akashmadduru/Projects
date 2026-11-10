package com.acme.ecommerce.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The concurrent-first-login race and its single retry.
 *
 * <p>THE SCENARIO: a user double-clicks "Sign in with Google". Two requests arrive for a {@code
 * sub} that has never been seen, both find no federated identity, and both decide to PROVISION. The
 * UNIQUE constraint on {@code (provider, provider_user_id)} lets exactly one succeed - which is the
 * point of having it - and the loser must recover silently rather than showing the user an error
 * for something they did nothing wrong to cause.
 */
@ExtendWith(MockitoExtension.class)
class FederatedLoginServiceTest {

  private static final OidcIdentity IDENTITY =
      new OidcIdentity("google", "sub-1", "race@example.com", true, "Racer");

  @Mock private FederatedIdentityResolver resolver;

  @InjectMocks private FederatedLoginService service;

  @Test
  @DisplayName("a losing concurrent provision is retried once and succeeds as a login")
  void retriesOnceAfterAConstraintViolation() {
    AuthenticatedPrincipal expected =
        new AuthenticatedPrincipal(
            UUID.randomUUID(), "race@example.com", Set.of("VIEWER"), AuthMethod.GOOGLE);

    when(resolver.resolve(IDENTITY))
        // First pass: we lost the race to insert the federated identity.
        .thenThrow(
            new DataIntegrityViolationException("duplicate key value violates unique constraint"))
        // Second pass: the winner has committed, so the row is now visible and
        // this becomes an ordinary login.
        .thenReturn(expected);

    assertThat(service.resolve(IDENTITY)).isEqualTo(expected);
    verify(resolver, times(2)).resolve(IDENTITY);
  }

  @Test
  @DisplayName("a second violation is a real constraint problem and is NOT retried into a loop")
  void doesNotRetryTwice() {
    when(resolver.resolve(IDENTITY))
        .thenThrow(new DataIntegrityViolationException("duplicate key"))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThatThrownBy(() -> service.resolve(IDENTITY))
        .isInstanceOf(AuthException.class)
        .extracting(e -> ((AuthException) e).authErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_OIDC_LOGIN_FAILED);

    verify(resolver, times(2)).resolve(IDENTITY);
  }

  @Test
  @DisplayName("an AuthException from the resolver passes straight through, unretried")
  void authExceptionIsNotRetried() {
    when(resolver.resolve(IDENTITY))
        .thenThrow(new AuthException(AuthErrorCode.AUTH_OIDC_EMAIL_UNVERIFIED));

    // Retrying a rejected login would double the work and could not change the
    // outcome - the IdP's assertion is not going to become verified on a retry.
    assertThatThrownBy(() -> service.resolve(IDENTITY)).isInstanceOf(AuthException.class);
    verify(resolver, times(1)).resolve(IDENTITY);
  }
}
