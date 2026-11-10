package com.acme.ecommerce.auth.token;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for refresh tokens.
 *
 * <p>PACKAGE-PRIVATE ON PURPOSE - this is the architectural enforcement of the rule that all
 * refresh-token state changes go through {@link TokenService} / {@link RefreshTokenService}. Spring
 * Data builds a JDK dynamic proxy in this same package, so package-private visibility costs nothing
 * at runtime and makes "just inject the repository and check the token yourself" a compile error
 * rather than a code-review conversation.
 *
 * <p>Every state transition below is expressed as a CONDITIONAL bulk UPDATE returning an
 * affected-row count, never as read-modify-write. That is not a micro-optimisation: read-then-write
 * on this table is a TOCTOU bug that lets two concurrent refreshes both believe they won and both
 * mint a child in the same family, which then makes the loser's next request look like token reuse
 * and revokes a perfectly innocent session.
 */
interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  /** The lookup that starts every refresh. Backed by the UNIQUE index on {@code token_hash}. */
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /**
   * THE COMPARE-AND-SET. Claims a token for rotation.
   *
   * <p>{@code AND revokedAt IS NULL} is the entire concurrency-control mechanism. Under Postgres
   * READ COMMITTED, a second transaction attempting the same UPDATE blocks on the row lock, then
   * re-evaluates the predicate against the committed row - and finds {@code revokedAt} set. It
   * therefore updates 0 rows and the caller knows, with certainty and without a SELECT, that it
   * lost the race.
   *
   * @return 1 if this caller won the right to rotate, 0 if it lost (or the row was already revoked)
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      update RefreshToken t
         set t.revokedAt = :now,
             t.rotatedAt = :now,
             t.revokedReason = 'ROTATED'
       where t.id = :id
         and t.revokedAt is null
      """)
  int claimForRotation(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Revokes every still-live token in a family. ONE indexed UPDATE, which is the entire reason the
   * family is denormalised onto each row instead of living in its own table.
   *
   * @return how many tokens were killed; &gt; 1 means the attacker and the victim were both holding
   *     live tokens, which is worth logging
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      update RefreshToken t
         set t.revokedAt = :now,
             t.revokedReason = :reason
       where t.familyId = :familyId
         and t.revokedAt is null
      """)
  int revokeFamily(
      @Param("familyId") UUID familyId, @Param("reason") String reason, @Param("now") Instant now);

  /** Revokes every live session for a user. Used on password change and by administrators. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      update RefreshToken t
         set t.revokedAt = :now,
             t.revokedReason = :reason
       where t.userId = :userId
         and t.revokedAt is null
      """)
  int revokeAllForUser(
      @Param("userId") UUID userId, @Param("reason") String reason, @Param("now") Instant now);

  /**
   * Marks a single token dead without touching its siblings. Used when a presented token is simply
   * past its expiry - an expired token is not evidence of an attack, so the family survives.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      update RefreshToken t
         set t.revokedAt = :now,
             t.revokedReason = 'EXPIRED'
       where t.id = :id
         and t.revokedAt is null
      """)
  int markExpired(@Param("id") UUID id, @Param("now") Instant now);

  /** Live token count for a family. Used by tests and by session-management tooling. */
  long countByFamilyIdAndRevokedAtIsNull(UUID familyId);

  /** Every token in a family, live or dead. Forensics and tests. */
  long countByFamilyId(UUID familyId);

  /**
   * Housekeeping: removes rows that can no longer affect any decision.
   *
   * <p>Deletes are safe here only because both predicates are terminal states - a revoked token can
   * never come back, and an expired one can never be rotated. Anything still live is untouched
   * regardless of age.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      delete from RefreshToken t
       where (t.revokedAt is not null and t.revokedAt < :cutoff)
          or (t.expiresAt < :cutoff)
      """)
  int deleteDeadTokensBefore(@Param("cutoff") Instant cutoff);
}
