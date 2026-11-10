package com.acme.ecommerce.auth.token;

import com.acme.ecommerce.common.core.time.TimeProvider;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional edge of the refresh-token table.
 *
 * <p>INTERNAL TO THIS PACKAGE. The class is package-private; its methods are public only because
 * Spring's proxy-based {@code @Transactional} ignores non-public methods entirely (see {@code
 * AbstractFallbackTransactionAttributeSource#allowPublicMethodsOnly}) - a package-private
 * {@code @Transactional} method silently runs with no transaction at all, which is exactly the kind
 * of bug this service cannot afford.
 *
 * <p>WHY EACH STEP IS ITS OWN TRANSACTION rather than one transaction around the whole rotation:
 * reuse detection must revoke a family and THEN throw a 401. If both happened inside one
 * transaction, the throw would roll the revocation back and the attacker's replay would leave no
 * trace and no consequence. {@code REQUIRES_NEW} on the mutating steps makes each one durable the
 * instant it completes, independently of what the caller does next.
 *
 * <p>The atomicity that actually matters is not provided by the transaction boundary anyway - it is
 * provided by the conditional UPDATE in {@link RefreshTokenRepository#claimForRotation}.
 */
@Service
class RefreshTokenStore {

  private final RefreshTokenRepository refreshTokens;
  private final TimeProvider timeProvider;

  RefreshTokenStore(RefreshTokenRepository refreshTokens, TimeProvider timeProvider) {
    this.refreshTokens = refreshTokens;
    this.timeProvider = timeProvider;
  }

  /** Looks up a token by the SHA-256 hex of its raw value. */
  @Transactional(readOnly = true)
  public Optional<RefreshTokenSnapshot> findByHash(String tokenHash) {
    return refreshTokens.findByTokenHash(tokenHash).map(RefreshTokenSnapshot::of);
  }

  /**
   * Attempts to claim a token for rotation.
   *
   * @return true if this caller won; false if the row was already revoked (lost race, or replay)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claimForRotation(UUID tokenId) {
    return refreshTokens.claimForRotation(tokenId, timeProvider.now()) == 1;
  }

  /** Marks a single expired token dead without touching the rest of its family. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markExpired(UUID tokenId) {
    refreshTokens.markExpired(tokenId, timeProvider.now());
  }

  /** Revokes every live token in a family. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int revokeFamily(UUID familyId, RevokeReason reason) {
    return refreshTokens.revokeFamily(familyId, reason.name(), timeProvider.now());
  }

  /** Deletes rows that can no longer affect any decision. Housekeeping only. */
  @Transactional
  public int deleteDeadTokensBefore(Instant cutoff) {
    return refreshTokens.deleteDeadTokensBefore(cutoff);
  }
}
