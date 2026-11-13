package com.acme.ecommerce.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_totp")
public class UserTotp {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "secret_enc", nullable = false, length = 200)
  private String secretEnc;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "last_used_step", nullable = false)
  private long lastUsedStep;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "enabled_at")
  private Instant enabledAt;

  protected UserTotp() {
    // JPA
  }

  public UserTotp(UUID userId, String secretEnc, Instant now) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.secretEnc = secretEnc;
    this.createdAt = now;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getSecretEnc() {
    return secretEnc;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public long getLastUsedStep() {
    return lastUsedStep;
  }

  public void markEnabled(long step, Instant now) {
    this.enabled = true;
    this.enabledAt = now;
    this.lastUsedStep = step;
  }

  public void useStep(long step) {
    this.lastUsedStep = step;
  }
}
