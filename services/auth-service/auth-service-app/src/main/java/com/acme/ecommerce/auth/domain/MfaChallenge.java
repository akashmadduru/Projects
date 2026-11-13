package com.acme.ecommerce.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mfa_challenges")
public class MfaChallenge {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
  private String tokenHash;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "auth_method", nullable = false, updatable = false, length = 20)
  private String authMethod;

  @Column(name = "attempts", nullable = false)
  private short attempts;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  protected MfaChallenge() {
    // JPA
  }

  public MfaChallenge(
      String tokenHash, UUID userId, String authMethod, Instant now, Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.tokenHash = tokenHash;
    this.userId = userId;
    this.authMethod = authMethod;
    this.createdAt = now;
    this.expiresAt = expiresAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getAuthMethod() {
    return authMethod;
  }

  public short getAttempts() {
    return attempts;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }
}
