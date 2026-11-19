package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.domain.UserCredential;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.repository.UserCredentialRepository;
import com.acme.ecommerce.common.core.time.TimeProvider;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the per-account brute-force counters.
 *
 * <p>SEPARATE BEAN, {@code REQUIRES_NEW}, ON PURPOSE. Recording a failed attempt is a write that
 * must survive the failure it is recording. If it ran in the caller's transaction - or as a
 * self-invocation, which is not proxied at all and so would run in no transaction - the exception
 * thrown immediately afterwards would roll the increment back, the counter would never advance, and
 * the lockout would quietly never trigger. That failure mode is invisible in every test that checks
 * only the HTTP status.
 *
 * <p>This is THE brute-force control. The per-IP Bucket4j throttle is per-replica and per-source-
 * address: an attacker with a botnet walks straight past it. This counter is in the shared database
 * and is attached to the account under attack.
 */
@Service
public class LoginAttemptService {

  private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

  private final UserAccountRepository users;
  private final UserCredentialRepository credentials;
  private final TimeProvider timeProvider;
  private final AuthProperties.Lockout policy;

  public LoginAttemptService(
      UserAccountRepository users,
      UserCredentialRepository credentials,
      TimeProvider timeProvider,
      AuthProperties properties) {
    this.users = users;
    this.credentials = credentials;
    this.timeProvider = timeProvider;
    this.policy = properties.lockout();
  }

  /**
   * Records a failed password attempt for an email address, if it belongs to a real account.
   *
   * <p>Silently does nothing for an unknown address. There is nowhere to record it (no row) and
   * inventing one would build exactly the user-enumeration table this service works to avoid.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(String email) {
    users
        .findByEmail(UserAccount.normaliseEmail(email))
        .ifPresent(user -> recordFailure(user.getId()));
  }

  /**
   * Records a failed attempt (password or OTP) against a known user. A user with no {@code
   * user_credentials} row (OTP-only or social-only) cannot hold lock state, so this does nothing.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(UUID userId) {
    Optional<UserCredential> credential = credentials.findById(userId);
    if (credential.isEmpty()) {
      return;
    }

    UserCredential entity = credential.get();
    boolean locked =
        entity.recordFailure(
            policy.maxFailedAttempts(),
            policy.baseDuration(),
            policy.maxDuration(),
            timeProvider.now());
    credentials.save(entity);

    if (locked) {
      // INFO, not WARN: a single user fat-fingering their password five times is
      // not an incident, and making it one trains people to ignore the log.
      log.info(
          "Account locked after {} failed attempts userId={} until={}",
          entity.getFailedAttempts(),
          entity.getUserId(),
          entity.getLockedUntil());
    }
  }

  /** True while the user's credential row holds a lock; false when there is no row. */
  @Transactional(readOnly = true)
  public boolean isLocked(UUID userId) {
    return credentials
        .findById(userId)
        .map(credential -> credential.isLockedAt(timeProvider.now()))
        .orElse(false);
  }

  /**
   * Clears the counters after a successful authentication.
   *
   * <p>Skips the write entirely when there is nothing to clear, which is the overwhelmingly common
   * case - otherwise every successful login would dirty a row and produce a pointless UPDATE plus
   * WAL traffic.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordSuccess(UUID userId) {
    credentials
        .findById(userId)
        .filter(
            credential -> credential.getFailedAttempts() > 0 || credential.getLockedUntil() != null)
        .ifPresent(
            credential -> {
              credential.recordSuccess();
              credentials.save(credential);
            });
  }
}
