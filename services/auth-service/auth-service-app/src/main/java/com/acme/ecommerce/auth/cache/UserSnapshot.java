package com.acme.ecommerce.auth.cache;

import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A minimal, denormalised view of a logged-in user, written to {@link UserSessionCache} on every
 * token issuance.
 *
 * <p>Deliberately NOT folded into {@link AuthenticatedPrincipal}: that record is documented as
 * "everything token issuance needs to know" for the JWT claims, and {@code displayName}/{@code
 * status} are not JWT claims. Keeping the two separate means a field that only ever belonged in
 * this cache cannot silently leak into a token later.
 *
 * <p>Purely a read-side performance aid for a future consumer (see
 * docs/HLD-event-driven-inventory.md S5) - never consulted on any authentication or authorization
 * decision.
 */
public record UserSnapshot(
    UUID id, String email, String displayName, Set<String> roles, String status) {

  public UserSnapshot {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(email, "email");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(status, "status");
    // Defensive immutable copy, same rationale as AuthenticatedPrincipal's.
    roles = Set.copyOf(roles == null ? Set.of() : roles);
  }

  /**
   * Builds the cache entry from the principal used to issue the token plus the backing account the
   * caller already has loaded - {@code TokenService} does not load {@link UserAccount} itself.
   */
  public static UserSnapshot from(AuthenticatedPrincipal principal, UserAccount account) {
    return new UserSnapshot(
        principal.userId(),
        principal.email(),
        account.getDisplayName(),
        principal.roles(),
        account.getStatus().name());
  }
}
