package com.acme.ecommerce.auth.token;

import com.acme.ecommerce.auth.api.dto.AuthMethod;
import java.time.Instant;
import java.util.UUID;

/**
 * An immutable read of one refresh-token row.
 *
 * <p>The rotation state machine reads the row in one transaction and then performs its
 * compare-and-set in ANOTHER, so it must not be holding a managed entity across the boundary - a
 * detached {@code RefreshToken} would either throw on lazy access or, worse, be silently
 * re-attached and flushed with stale values. Copying the handful of fields the decision needs makes
 * the boundary explicit and the state machine trivially unit-testable.
 *
 * @param id primary key
 * @param userId owner
 * @param familyId the family this token belongs to
 * @param authMethod how the session was originally authenticated; carried unchanged through every
 *     rotation, so a session that began with a password never silently becomes a Google session
 * @param expiresAt sliding expiry
 * @param familyAbsoluteExpiresAt hard cap inherited from the family root
 * @param rotatedAt when this token was exchanged for a child, if it was
 * @param revokedAt when this token stopped being usable, if it has
 * @param revokedReason why
 */
record RefreshTokenSnapshot(
    UUID id,
    UUID userId,
    UUID familyId,
    AuthMethod authMethod,
    Instant expiresAt,
    Instant familyAbsoluteExpiresAt,
    Instant rotatedAt,
    Instant revokedAt,
    String revokedReason) {

  static RefreshTokenSnapshot of(RefreshToken token) {
    return new RefreshTokenSnapshot(
        token.getId(),
        token.getUserId(),
        token.getFamilyId(),
        token.getAuthMethod(),
        token.getExpiresAt(),
        token.getFamilyAbsoluteExpiresAt(),
        token.getRotatedAt(),
        token.getRevokedAt(),
        token.getRevokedReason());
  }

  /** True when the row is still live. */
  boolean isActive() {
    return revokedAt == null;
  }

  /** True when either the sliding window or the family's absolute cap has passed. */
  boolean isExpiredAt(Instant now) {
    return !expiresAt.isAfter(now) || !familyAbsoluteExpiresAt.isAfter(now);
  }

  /**
   * True when this row was revoked by a NORMAL ROTATION - i.e. presenting it again is a replay of a
   * spent token.
   *
   * <p>Any other revocation reason (logout, password change, an earlier family revocation) means
   * the session was already deliberately terminated, so a stale cookie arriving afterwards is
   * expected client behaviour rather than evidence of theft. Treating those as reuse would fire the
   * security alert every time a user closed a second tab after logging out.
   */
  boolean wasRotated() {
    return RevokeReason.ROTATED.name().equals(revokedReason);
  }
}
