package com.acme.ecommerce.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recovery_codes")
public class RecoveryCode {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
  private String codeHash;

  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected RecoveryCode() {
    // JPA
  }

  public RecoveryCode(UUID userId, String codeHash, Instant now) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.codeHash = codeHash;
    this.createdAt = now;
  }
}
