package com.acme.ecommerce.auth.token;

import com.acme.ecommerce.auth.api.dto.AuthMethod;
import java.util.UUID;

/**
 * Rebuilds an {@link AuthenticatedPrincipal} from a user id.
 *
 * <p>A refresh token stores only {@code user_id} and {@code auth_method}, never the roles that were
 * in effect when it was issued. That is deliberate: if an administrator revokes a role, the change
 * must take effect at the next refresh (at most one access-token TTL later), not whenever the user
 * next chooses to log in. Snapshotting roles into the token row would make revocation take up to 14
 * days.
 *
 * <p>Declared here, in the token package, and implemented in the service package: it inverts the
 * dependency so that token issuance never has to know about user repositories, and so the rotation
 * state machine can be unit-tested with a two-line stub.
 */
public interface PrincipalLoader {

  /**
   * Loads the principal for an existing session.
   *
   * @throws com.acme.ecommerce.auth.error.AuthException if the user no longer exists or may no
   *     longer authenticate (disabled/locked). A refresh must fail closed: the whole point of a
   *     10-minute access token is that de-provisioning takes effect promptly.
   */
  AuthenticatedPrincipal load(UUID userId, AuthMethod authMethod);
}
