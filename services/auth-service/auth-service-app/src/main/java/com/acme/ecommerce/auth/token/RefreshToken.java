package com.acme.ecommerce.auth.token;

import com.acme.ecommerce.auth.api.dto.AuthMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.domain.Persistable;

/**
 * One link in a refresh-token chain.
 *
 * <p>PACKAGE-PRIVATE ON PURPOSE. Together with {@link RefreshTokenRepository} this type is the
 * mechanism the whole session-security model rests on, so it is not reachable from a controller, a
 * service, or a future feature that "just needs to check something". The only ways in are {@link
 * TokenService} and {@link RefreshTokenService}. If you find yourself wanting to widen this
 * visibility, add a method to one of those two instead.
 *
 * <p>The raw token value is NOT a field here and never will be. Only {@link #getTokenHash()}
 * exists.
 *
 * <p>Implements {@link Persistable} because the id is assigned in the constructor rather than
 * generated at flush: a family root has to know its own id in order to set {@code familyId = id}.
 * Without the explicit {@code isNew} flag, Spring Data would see a non-null id, assume the entity
 * is detached, and issue a SELECT before every INSERT - one wasted round trip on the hottest write
 * path in the service.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken implements Persistable<UUID> {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
  private String tokenHash;

  @Column(name = "family_id", nullable = false, updatable = false)
  private UUID familyId;

  @Column(name = "parent_id", updatable = false)
  private UUID parentId;

  @Column(name = "auth_method", nullable = false, length = 20, updatable = false)
  private String authMethod;

  @Column(name = "issued_at", nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "family_absolute_expires_at", nullable = false, updatable = false)
  private Instant familyAbsoluteExpiresAt;

  @Column(name = "rotated_at")
  private Instant rotatedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoked_reason", length = 32)
  private String revokedReason;

  @Column(name = "user_agent", length = 400, updatable = false)
  private String userAgent;

  /**
   * Postgres {@code inet}.
   *
   * <p>pgjdbc sends a {@code String} parameter typed as {@code varchar}, and Postgres refuses to
   * assign a varchar to an {@code inet} column. The write transformer adds the explicit cast, which
   * is cheaper and far less surprising than the alternative of setting {@code
   * stringtype=unspecified} on the JDBC URL - a global switch that changes parameter typing for
   * every statement the service issues.
   */
  @Column(name = "ip_address", updatable = false, columnDefinition = "inet")
  @ColumnTransformer(write = "?::inet")
  private String ipAddress;

  @Transient private boolean persisted;

  protected RefreshToken() {
    // JPA
  }

  private RefreshToken(
      UUID id,
      UUID userId,
      String tokenHash,
      UUID familyId,
      UUID parentId,
      AuthMethod authMethod,
      Instant issuedAt,
      Instant expiresAt,
      Instant familyAbsoluteExpiresAt,
      DeviceContext device) {
    this.id = id;
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.familyId = familyId;
    this.parentId = parentId;
    this.authMethod = authMethod.claimValue();
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
    this.familyAbsoluteExpiresAt = familyAbsoluteExpiresAt;
    DeviceContext safe = device == null ? DeviceContext.unknown() : device.truncated();
    this.userAgent = safe.userAgent();
    this.ipAddress = safe.ipAddress();
  }

  /**
   * Creates the ROOT of a new family: a fresh login.
   *
   * <p>{@code familyId} is this row's own id, and {@code familyAbsoluteExpiresAt} is fixed here,
   * once, for the entire lifetime of the chain.
   */
  static RefreshToken newFamilyRoot(
      UUID userId,
      String tokenHash,
      AuthMethod authMethod,
      Instant issuedAt,
      Instant expiresAt,
      Instant familyAbsoluteExpiresAt,
      DeviceContext device) {
    UUID id = UUID.randomUUID();
    return new RefreshToken(
        id,
        userId,
        tokenHash,
        id,
        null,
        authMethod,
        issuedAt,
        expiresAt,
        familyAbsoluteExpiresAt,
        device);
  }

  /**
   * Creates a CHILD of an existing token.
   *
   * <p>{@code familyAbsoluteExpiresAt} is a parameter, not a computation. It is copied from the
   * parent verbatim. Recomputing it as {@code now + 30d} here would push the cap forward on every
   * rotation and turn a 30-day maximum session into an unbounded one - the single subtlest way to
   * silently delete this control.
   */
  static RefreshToken child(
      UUID userId,
      String tokenHash,
      UUID familyId,
      UUID parentId,
      AuthMethod authMethod,
      Instant issuedAt,
      Instant expiresAt,
      Instant familyAbsoluteExpiresAt,
      DeviceContext device) {
    return new RefreshToken(
        UUID.randomUUID(),
        userId,
        tokenHash,
        Objects.requireNonNull(familyId, "familyId"),
        Objects.requireNonNull(parentId, "parentId"),
        authMethod,
        issuedAt,
        expiresAt,
        familyAbsoluteExpiresAt,
        device);
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return !persisted;
  }

  @PostPersist
  @PostLoad
  void markPersisted() {
    this.persisted = true;
  }

  UUID getUserId() {
    return userId;
  }

  String getTokenHash() {
    return tokenHash;
  }

  UUID getFamilyId() {
    return familyId;
  }

  UUID getParentId() {
    return parentId;
  }

  AuthMethod getAuthMethod() {
    return AuthMethod.fromClaimValue(authMethod);
  }

  Instant getIssuedAt() {
    return issuedAt;
  }

  Instant getExpiresAt() {
    return expiresAt;
  }

  Instant getFamilyAbsoluteExpiresAt() {
    return familyAbsoluteExpiresAt;
  }

  Instant getRotatedAt() {
    return rotatedAt;
  }

  Instant getRevokedAt() {
    return revokedAt;
  }

  String getUserAgent() {
    return userAgent;
  }

  String getIpAddress() {
    return ipAddress;
  }

  String getAuthMethodRaw() {
    return authMethod;
  }

  String getRevokedReason() {
    return revokedReason;
  }

  /** Never includes the hash: it is a bearer-equivalent lookup key. */
  @Override
  public String toString() {
    return "RefreshToken[id="
        + id
        + ", userId="
        + userId
        + ", familyId="
        + familyId
        + ", expiresAt="
        + expiresAt
        + ", revokedAt="
        + revokedAt
        + ", revokedReason="
        + revokedReason
        + "]";
  }
}
