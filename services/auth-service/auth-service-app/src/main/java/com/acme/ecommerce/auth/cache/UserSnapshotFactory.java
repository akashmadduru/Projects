package com.acme.ecommerce.auth.cache;

import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link UserSnapshot} written to Redis by {@code TokenService}, from an extra, plain
 * (no-roles-needed) lookup of the just-authenticated account.
 *
 * <p>Every {@code issueTokenPair} call site (password login, refresh rotation, Google login) needs
 * this same lookup - none of them otherwise hold a loaded {@code UserAccount} at the point they
 * call it - so it lives here once instead of as three identical private methods.
 *
 * <p>{@code null} rather than a thrown exception on a miss: this exists only to feed an optional
 * cache, and a user that vanished in the instant between authentication and this lookup must never
 * turn an otherwise-successful login/refresh into an error.
 */
@Component
public class UserSnapshotFactory {

  private final UserAccountRepository users;

  public UserSnapshotFactory(UserAccountRepository users) {
    this.users = users;
  }

  public UserSnapshot snapshotFor(AuthenticatedPrincipal principal) {
    return users
        .findById(principal.userId())
        .map(account -> UserSnapshot.from(principal, account))
        .orElse(null);
  }
}
