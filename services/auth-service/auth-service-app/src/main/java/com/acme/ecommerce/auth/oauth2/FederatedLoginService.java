package com.acme.ecommerce.auth.oauth2;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Wraps {@link FederatedIdentityResolver} with the concurrent-first-login retry.
 *
 * <p>THE RACE: a user double-clicks "Sign in with Google", or the SPA opens the flow in two tabs.
 * Two requests arrive for a {@code sub} that has never been seen, both find no {@code
 * federated_identities} row, and both decide to PROVISION. One wins; the other hits the UNIQUE
 * constraint on {@code (provider, provider_user_id)} - which exists precisely so that this cannot
 * end with two local accounts for one Google identity.
 *
 * <p>THE RECOVERY: retry exactly once. By the time the violation surfaces, the winner's transaction
 * has committed, so the second pass takes the LOGIN branch and the user never sees an error. A
 * second failure is not a race - it is a real constraint problem - and is surfaced rather than
 * retried into a loop.
 *
 * <p>The retry MUST live in a different bean from the {@code @Transactional} method it retries.
 * Catching the violation inside that method would leave the caller holding a transaction already
 * marked rollback-only, and calling it again via {@code this.} would not go through the proxy at
 * all, so the "retry" would run with no transaction and fail differently.
 */
@Service
public class FederatedLoginService {

  private static final Logger log = LoggerFactory.getLogger(FederatedLoginService.class);

  private final FederatedIdentityResolver resolver;

  public FederatedLoginService(FederatedIdentityResolver resolver) {
    this.resolver = resolver;
  }

  /**
   * Resolves an IdP assertion to a local principal.
   *
   * @throws AuthException when the login must be refused
   */
  public AuthenticatedPrincipal resolve(OidcIdentity identity) {
    try {
      return resolver.resolve(identity);
    } catch (DataIntegrityViolationException first) {
      log.info(
          "Concurrent first federated login for provider={} - retrying as an existing-link login",
          identity.provider());
      try {
        return resolver.resolve(identity);
      } catch (DataIntegrityViolationException second) {
        // Not a race: a genuine constraint problem (for example the email is
        // already taken by a different subject). Refuse rather than retry.
        throw new AuthException(
            AuthErrorCode.AUTH_OIDC_LOGIN_FAILED,
            "Federated login failed after retry for provider "
                + identity.provider()
                + ": "
                + second.getMostSpecificCause().getMessage());
      }
    }
  }
}
