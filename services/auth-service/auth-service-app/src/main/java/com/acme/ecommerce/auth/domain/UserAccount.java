package com.acme.ecommerce.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A platform identity.
 *
 * <p>Holds no credential material of any kind. The password hash lives in {@link UserCredential}
 * and IdP subjects live in {@link FederatedIdentity}, so a query for a user can never accidentally
 * drag a secret along with it.
 *
 * <p>FETCHING: {@code roles} is {@code LAZY}, which is the only safe default - eager on a
 * {@code @ManyToMany} means every single user lookup, including the one on the login hot path, pays
 * for a join it usually does not need. The paths that DO need roles (login, {@code /me}) ask for
 * them explicitly with an {@code @EntityGraph} on the repository method, which produces one query
 * rather than the N+1 that lazy-plus-loop would.
 */
@Entity
@Table(name = "users")
public class UserAccount {

  @Id
  @GeneratedValue(strategy = jakarta.persistence.GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "email", nullable = false, length = 320)
  private String email;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified;

  @Column(name = "phone", length = 20)
  private String phone;

  @Column(name = "phone_verified", nullable = false)
  private boolean phoneVerified;

  @Column(name = "display_name", nullable = false, length = 200)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private UserStatus status = UserStatus.ACTIVE;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  /**
   * Owning side of the user - role association.
   *
   * <p>{@code CascadeType} is deliberately absent: roles are reference data seeded by the
   * migration. A cascade here would let saving a user attempt to insert or delete a role row.
   */
  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "user_roles",
      joinColumns = @JoinColumn(name = "user_id"),
      inverseJoinColumns = @JoinColumn(name = "role_id"))
  private Set<RoleEntity> roles = new LinkedHashSet<>();

  protected UserAccount() {
    // JPA
  }

  private UserAccount(String email, String displayName, boolean emailVerified) {
    this.email = normaliseEmail(email);
    this.displayName = displayName;
    this.emailVerified = emailVerified;
    this.status = UserStatus.ACTIVE;
  }

  /** Creates a new ACTIVE user. Roles are granted separately by the calling service. */
  public static UserAccount create(String email, String displayName, boolean emailVerified) {
    return new UserAccount(
        Objects.requireNonNull(email, "email"),
        Objects.requireNonNull(displayName, "displayName"),
        emailVerified);
  }

  /**
   * Canonical form of an email address: trimmed and lower-cased.
   *
   * <p>Applied on every write AND every lookup. See the note in {@code V1__auth_baseline.sql}: the
   * {@code citext} column guarantees the UNIQUE constraint is case-insensitive, but a JDBC {@code
   * varchar} parameter compared against a {@code citext} column resolves to a case-SENSITIVE text
   * comparison, so the application cannot delegate normalisation to the database.
   */
  public static String normaliseEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }

  @PrePersist
  void onPersist() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  // --- behaviour -------------------------------------------------------------

  /** Grants a role. Idempotent. */
  public void grant(RoleEntity role) {
    roles.add(Objects.requireNonNull(role, "role"));
  }

  /** Marks the address as proven. Used when an IdP asserts {@code email_verified=true}. */
  public void markEmailVerified() {
    this.emailVerified = true;
  }

  /** Sets the (unverified) phone number. Changing the number clears verification. */
  public void changePhone(String e164) {
    if (!Objects.equals(this.phone, e164)) {
      this.phone = e164;
      this.phoneVerified = false;
    }
  }

  /** Marks the current phone number as proven. */
  public void markPhoneVerified() {
    this.phoneVerified = true;
  }

  public void disable() {
    this.status = UserStatus.DISABLED;
  }

  /** Whether this identity is permitted to authenticate at all. */
  public boolean canAuthenticate() {
    return status == UserStatus.ACTIVE;
  }

  // --- accessors -------------------------------------------------------------

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public boolean isEmailVerified() {
    return emailVerified;
  }

  public String getPhone() {
    return phone;
  }

  public boolean isPhoneVerified() {
    return phoneVerified;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public UserStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Long getVersion() {
    return version;
  }

  /** Unmodifiable view; use {@link #grant(RoleEntity)} to mutate. */
  public Set<RoleEntity> getRoles() {
    return java.util.Collections.unmodifiableSet(roles);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof UserAccount user && id != null && id.equals(user.id);
  }

  @Override
  public int hashCode() {
    // Constant, NOT Objects.hash(id): the id is null until flush, so an
    // id-derived hash changes after persist and the entity is lost in any
    // HashSet it was added to beforehand.
    return Objects.hashCode(UserAccount.class);
  }

  @Override
  public String toString() {
    return "UserAccount[id=" + id + ", email=" + email + ", status=" + status + "]";
  }
}
