package com.acme.ecommerce.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "known_devices")
public class KnownDevice {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "fingerprint", nullable = false, updatable = false, length = 64)
  private String fingerprint;

  @Column(name = "first_seen", nullable = false, updatable = false)
  private Instant firstSeen;

  @Column(name = "last_seen", nullable = false)
  private Instant lastSeen;

  protected KnownDevice() {
    // JPA
  }

  public KnownDevice(UUID userId, String fingerprint, Instant now) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.fingerprint = fingerprint;
    this.firstSeen = now;
    this.lastSeen = now;
  }
}
