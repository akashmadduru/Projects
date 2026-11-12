package com.acme.ecommerce.auth.repository;

import com.acme.ecommerce.auth.domain.OneTimeToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OneTimeTokenRepository extends JpaRepository<OneTimeToken, UUID> {

  Optional<OneTimeToken> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(
      UUID userId, String purpose);

  Optional<OneTimeToken> findByPurposeAndTokenHash(String purpose, String tokenHash);

  @Modifying
  @Query("delete from OneTimeToken t where t.userId = :userId and t.purpose = :purpose")
  int deleteForUser(@Param("userId") UUID userId, @Param("purpose") String purpose);

  /** Atomic single-use claim: exactly one caller gets 1. */
  @Modifying
  @Query(
      """
      update OneTimeToken t set t.consumedAt = :now
       where t.id = :id and t.consumedAt is null and t.expiresAt > :now
      """)
  int consume(@Param("id") UUID id, @Param("now") Instant now);

  /** Atomically spends one attempt; returns 0 when none are left (race-safe cap). */
  @Modifying
  @Query(
      "update OneTimeToken t set t.attempts = t.attempts + 1"
          + " where t.id = :id and t.attempts < :max")
  int spendAttempt(@Param("id") UUID id, @Param("max") int max);

  @Modifying
  @Query("delete from OneTimeToken t where t.expiresAt < :cutoff")
  int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
