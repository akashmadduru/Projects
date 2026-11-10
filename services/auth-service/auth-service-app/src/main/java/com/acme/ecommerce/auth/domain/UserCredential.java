package com.acme.ecommerce.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The local password proof for a user, plus its brute-force counters.
 *
 * <p>1:1 with {@link UserAccount}, sharing its primary key. Social-only users simply have no row
 * here, which is a far clearer model than a nullable {@code password_hash} column that every code
 * path has to null-check.
 *
 * <p>THE LOCKOUT HERE IS THE REAL BRUTE-FORCE CONTROL. The Bucket4j per-IP throttle is per-instance
 * and per-source-address: an attacker with a botnet walks straight through it. This counter lives
 * in the shared database, so it is correct with one replica or fifty, and it is attached to the
 * thing actually under attack (the account) rather than to the thing the attacker controls (the
 * source IP).
 */
@Entity
@Table(name = "user_credentials")
public class UserCredential {

  @Id
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Column(name = "algorithm", nullable = false, length = 32)
  private String algorithm;

  @Column(name = "password_updated_at", nullable = false)
  private Instant passwordUpdatedAt;

  @Column(name = "failed_attempts", nullable = false)
  private short failedAttempts;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "must_change_password", nullable = false)
  private boolean mustChangePassword;

  protected UserCredential() {
    // JPA
  }

  private UserCredential(UUID userId, String passwordHash, String algorithm, Instant now) {
    this.userId = userId;
    this.passwordHash = passwordHash;
    this.algorithm = algorithm;
    this.passwordUpdatedAt = now;
    this.failedAttempts = 0;
  }

  /**
   * Creates a credential row.
   *
   * @param encodedPassword the already-encoded value from the {@code PasswordEncoder}; this class
   *     never sees or hashes a plaintext password itself
   */
  public static UserCredential create(UUID userId, String encodedPassword, Instant now) {
    return new UserCredential(
        Objects.requireNonNull(userId, "userId"),
        Objects.requireNonNull(encodedPassword, "encodedPassword"),
        algorithmIdOf(encodedPassword),
        Objects.requireNonNull(now, "now"));
  }

  /**
   * Extracts the {@code {id}} prefix Spring's {@code DelegatingPasswordEncoder} writes, so the
   * {@code algorithm} column stays in step with the hash without the caller having to remember to
   * pass it.
   */
  private static String algorithmIdOf(String encodedPassword) {
    if (encodedPassword.startsWith("{")) {
      int end = encodedPassword.indexOf('}');
      if (end > 1) {
        return encodedPassword.substring(1, end);
      }
    }
    return "unknown";
  }

  // --- behaviour -------------------------------------------------------------

  /** True while a transient brute-force lockout is in force. */
  public boolean isLockedAt(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  /**
   * Records a failed attempt and applies the lockout policy.
   *
   * <p>Backoff is exponential in the number of failures past the threshold, capped: 5 failures ->
   * {@code base}, 6 -> {@code 2 x base}, 7 -> {@code 4 x base}, and so on up to {@code maxLockout}.
   * A linear policy is trivially outlasted; an uncapped exponential one turns a bored attacker into
   * a permanent denial of service against a real user.
   *
   * @return true if this failure resulted in (or extended) a lock
   */
  public boolean recordFailure(int threshold, Duration base, Duration maxLockout, Instant now) {
    if (failedAttempts < Short.MAX_VALUE) {
      failedAttempts++;
    }
    if (failedAttempts < threshold) {
      return false;
    }
    int overshoot = Math.min(failedAttempts - threshold, 16);
    long seconds = base.toSeconds() << overshoot;
    Duration lockFor =
        Duration.ofSeconds(
            Math.min(seconds <= 0 ? maxLockout.toSeconds() : seconds, maxLockout.toSeconds()));
    this.lockedUntil = now.plus(lockFor);
    return true;
  }

  /** Clears the counters after a successful authentication. */
  public void recordSuccess() {
    this.failedAttempts = 0;
    this.lockedUntil = null;
  }

  /** Replaces the hash and resets the counters. Caller must revoke existing refresh families. */
  public void changePassword(String encodedPassword, Instant now) {
    this.passwordHash = Objects.requireNonNull(encodedPassword, "encodedPassword");
    this.algorithm = algorithmIdOf(encodedPassword);
    this.passwordUpdatedAt = now;
    this.mustChangePassword = false;
    recordSuccess();
  }

  // --- accessors -------------------------------------------------------------

  public UUID getUserId() {
    return userId;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getAlgorithm() {
    return algorithm;
  }

  public Instant getPasswordUpdatedAt() {
    return passwordUpdatedAt;
  }

  public short getFailedAttempts() {
    return failedAttempts;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  public boolean isMustChangePassword() {
    return mustChangePassword;
  }

  /** Never includes the hash. A credential in a log line is a credential in an incident report. */
  @Override
  public String toString() {
    return "UserCredential[userId="
        + userId
        + ", algorithm="
        + algorithm
        + ", failedAttempts="
        + failedAttempts
        + ", lockedUntil="
        + lockedUntil
        + ", passwordHash=***]";
  }
}
