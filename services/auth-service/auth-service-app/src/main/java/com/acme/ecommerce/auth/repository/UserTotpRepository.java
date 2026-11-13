package com.acme.ecommerce.auth.repository;

import com.acme.ecommerce.auth.domain.UserTotp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserTotpRepository extends JpaRepository<UserTotp, UUID> {

  @Query("select t from UserTotp t where t.userId = :userId")
  Optional<UserTotp> findByUser(@Param("userId") UUID userId);

  @Query("select count(t) > 0 from UserTotp t where t.userId = :userId and t.enabled = true")
  boolean isEnabled(@Param("userId") UUID userId);

  @Transactional
  @Modifying
  @Query("delete from UserTotp t where t.userId = :userId")
  int deleteForUser(@Param("userId") UUID userId);

  /** Atomically advances the replay guard; 0 rows means the step was already used. */
  @Transactional
  @Modifying
  @Query(
      "update UserTotp t set t.lastUsedStep = :step"
          + " where t.userId = :userId and t.lastUsedStep < :step")
  int advanceStep(@Param("userId") UUID userId, @Param("step") long step);
}
