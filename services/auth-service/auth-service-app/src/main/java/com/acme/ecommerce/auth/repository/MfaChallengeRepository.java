package com.acme.ecommerce.auth.repository;

import com.acme.ecommerce.auth.domain.MfaChallenge;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MfaChallengeRepository extends JpaRepository<MfaChallenge, UUID> {

  Optional<MfaChallenge> findByTokenHash(String tokenHash);

  @Transactional
  @Modifying
  @Query("update MfaChallenge c set c.attempts = c.attempts + 1 where c.id = :id")
  int spendAttempt(@Param("id") UUID id);

  @Transactional
  @Modifying
  @Query(
      "update MfaChallenge c set c.consumedAt = :now"
          + " where c.id = :id and c.consumedAt is null and c.expiresAt > :now")
  int consume(@Param("id") UUID id, @Param("now") Instant now);

  @Transactional
  @Modifying
  @Query("delete from MfaChallenge c where c.expiresAt < :cutoff")
  int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
