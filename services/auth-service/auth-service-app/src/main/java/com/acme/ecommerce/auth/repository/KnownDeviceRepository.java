package com.acme.ecommerce.auth.repository;

import com.acme.ecommerce.auth.domain.KnownDevice;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface KnownDeviceRepository extends JpaRepository<KnownDevice, UUID> {

  long countByUserId(UUID userId);

  /** Touches last_seen; returns 0 when the fingerprint has never been seen for the user. */
  @Transactional
  @Modifying
  @Query(
      "update KnownDevice d set d.lastSeen = :now"
          + " where d.userId = :userId and d.fingerprint = :fingerprint")
  int touch(
      @Param("userId") UUID userId,
      @Param("fingerprint") String fingerprint,
      @Param("now") Instant now);
}
