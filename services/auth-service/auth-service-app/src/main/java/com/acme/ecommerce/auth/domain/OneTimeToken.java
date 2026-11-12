package com.acme.ecommerce.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A single-use secret: password-reset link token, email-verification link token, or a 6-digit
 * code (phone verification / OTP login). Only the SHA-256 hash is stored.
 */
@Entity
@Table(name = "one_time_tokens")
public class OneTimeToken {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "purpose", nullable = false, updatable = false, length = 30)
  private String purpose;

  @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
  private String tokenHash;

  @Column(name = "target", length = 320, updatable = false)
  private String target;

  @Column(name = "attempts", nullable = false)
  private short attempts;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  protected OneTimeToken() {
    // JPA
  }

  public OneTimeToken(
      UUID userId, String purpose, String tokenHash, String target, Instant now, Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.purpose = purpose;
    this.tokenHash = tokenHash;
    this.target = target;
    this.createdAt = now;
    this.expiresAt = expiresAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getPurpose() {
    return purpose;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public String getTarget() {
    return target;
  }

  public short getAttempts() {
    return attempts;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }
}
