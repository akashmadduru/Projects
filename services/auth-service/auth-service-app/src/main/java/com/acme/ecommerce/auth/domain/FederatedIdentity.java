package com.acme.ecommerce.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A link between a local {@link UserAccount} and an account at an external identity provider.
 *
 * <p>THE LOOKUP KEY IS {@code (provider, providerUserId)} AND NOTHING ELSE. {@code providerUserId}
 * is the OIDC {@code sub} claim. Matching on the provider's email address instead would mean that
 * when Google reassigns an address - or when a user changes theirs - the local account silently
 * follows whoever holds the address today. {@code sub} is immutable and non-reassignable; email is
 * neither.
 *
 * <p>{@code providerEmail} is stored for support and debugging only. Nothing authenticates on it.
 */
@Entity
@Table(name = "federated_identities")
public class FederatedIdentity {

  /** The only provider wired up in Phase 1C. */
  public static final String PROVIDER_GOOGLE = "google";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "provider", nullable = false, length = 50, updatable = false)
  private String provider;

  @Column(name = "provider_user_id", nullable = false, length = 255, updatable = false)
  private String providerUserId;

  @Column(name = "provider_email", length = 320)
  private String providerEmail;

  @Column(name = "email_verified_at_link", nullable = false, updatable = false)
  private boolean emailVerifiedAtLink;

  @Column(name = "linked_at", nullable = false, updatable = false)
  private Instant linkedAt;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  protected FederatedIdentity() {
    // JPA
  }

  private FederatedIdentity(
      UUID userId,
      String provider,
      String providerUserId,
      String providerEmail,
      boolean emailVerifiedAtLink,
      Instant now) {
    this.userId = userId;
    this.provider = provider;
    this.providerUserId = providerUserId;
    this.providerEmail = UserAccount.normaliseEmail(providerEmail);
    this.emailVerifiedAtLink = emailVerifiedAtLink;
    this.linkedAt = now;
    this.lastLoginAt = now;
  }

  /**
   * Links a Google account to a local user.
   *
   * @param emailVerifiedAtLink the IdP's {@code email_verified} assertion AT THE MOMENT OF LINKING.
   *     Recorded as audit evidence for the one decision that could have permitted an account
   *     takeover; callers must refuse to link at all when it is false.
   */
  public static FederatedIdentity link(
      UUID userId,
      String provider,
      String providerUserId,
      String providerEmail,
      boolean emailVerifiedAtLink,
      Instant now) {
    return new FederatedIdentity(
        Objects.requireNonNull(userId, "userId"),
        Objects.requireNonNull(provider, "provider"),
        Objects.requireNonNull(providerUserId, "providerUserId"),
        providerEmail,
        emailVerifiedAtLink,
        Objects.requireNonNull(now, "now"));
  }

  /** Records a successful federated login. */
  public void recordLogin(String currentProviderEmail, Instant now) {
    this.lastLoginAt = now;
    String normalised = UserAccount.normaliseEmail(currentProviderEmail);
    if (normalised != null) {
      this.providerEmail = normalised;
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getProvider() {
    return provider;
  }

  public String getProviderUserId() {
    return providerUserId;
  }

  public String getProviderEmail() {
    return providerEmail;
  }

  public boolean isEmailVerifiedAtLink() {
    return emailVerifiedAtLink;
  }

  public Instant getLinkedAt() {
    return linkedAt;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }

  @Override
  public String toString() {
    return "FederatedIdentity[id="
        + id
        + ", userId="
        + userId
        + ", provider="
        + provider
        + ", linkedAt="
        + linkedAt
        + "]";
  }
}
