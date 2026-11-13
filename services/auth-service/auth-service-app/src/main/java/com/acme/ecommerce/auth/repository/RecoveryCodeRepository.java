package com.acme.ecommerce.auth.repository;

import com.acme.ecommerce.auth.domain.RecoveryCode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, UUID> {

  @Transactional
  @Modifying
  @Query("delete from RecoveryCode c where c.userId = :userId")
  int deleteForUser(@Param("userId") UUID userId);

  /** Single-use: returns 1 only for the caller that actually spent the code. */
  @Transactional
  @Modifying
  @Query(
      "update RecoveryCode c set c.usedAt = :now"
          + " where c.userId = :userId and c.codeHash = :hash and c.usedAt is null")
  int spend(
      @Param("userId") UUID userId, @Param("hash") String hash, @Param("now") Instant now);

  @Query("select count(c) from RecoveryCode c where c.userId = :userId and c.usedAt is null")
  long countUnused(@Param("userId") UUID userId);
}
