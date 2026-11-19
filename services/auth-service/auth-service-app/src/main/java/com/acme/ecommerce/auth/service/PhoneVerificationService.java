package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.service.OneTimeTokenService.Purpose;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Add and verify a phone number for the signed-in user.
 *
 * <p>Not {@code @Transactional}: a wrong code must throw 401 WITHOUT rolling back the attempt
 * counter, so each unit of work (code check, user update) commits on its own.
 */
@Service
public class PhoneVerificationService {

  private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);

  static final Duration CODE_TTL = Duration.ofMinutes(10);
  static final Duration RESEND_COOLDOWN = Duration.ofSeconds(30);
  static final int MAX_ATTEMPTS = 5;

  private final UserAccountRepository users;
  private final OneTimeTokenService tokens;
  private final NotificationGateway notifications;
  private final PhoneClaimNotifier claimNotifier;
  private final TransactionTemplate tx;

  public PhoneVerificationService(
      UserAccountRepository users,
      OneTimeTokenService tokens,
      NotificationGateway notifications,
      PhoneClaimNotifier claimNotifier,
      TransactionTemplate tx) {
    this.users = users;
    this.tokens = tokens;
    this.notifications = notifications;
    this.claimNotifier = claimNotifier;
    this.tx = tx;
  }

  /** Stores the (unverified) number on the account and texts a code. */
  public void addPhone(UUID userId, String rawPhone) {
    String phone =
        PhoneNumbers.normalise(rawPhone)
            .orElseThrow(
                () ->
                    new AuthException(
                        AuthErrorCode.AUTH_PHONE_INVALID, "Not a valid E.164 number"));
    // Taken and free numbers run the same steps and answer the same (no enumeration oracle).
    boolean taken = users.existsByPhoneAndPhoneVerifiedTrueAndIdNot(phone, userId);
    Boolean alreadyVerified =
        tx.execute(
            status -> {
              UserAccount user = users.findById(userId).orElseThrow();
              user.changePhone(phone);
              return user.isPhoneVerified();
            });
    if (Boolean.TRUE.equals(alreadyVerified)) {
      return;
    }
    Optional<String> code =
        tokens.issueCode(userId, Purpose.PHONE_VERIFY, phone, CODE_TTL, RESEND_COOLDOWN);
    if (code.isEmpty()) {
      throw new AuthException(AuthErrorCode.AUTH_RATE_LIMITED, "Phone code resend cooldown active");
    }
    if (taken) {
      // No SMS: the code goes nowhere, and the verified owner is told (at most once a day).
      claimNotifier.attempted(phone);
    } else {
      notifications.sendSms(
          phone, "Your verification code is " + code.get() + ". It expires in 10 minutes.");
    }
  }

  /** Checks the code and marks the number verified. */
  public void verifyPhone(UUID userId, String code) {
    UserAccount user = users.findById(userId).orElseThrow();
    String phone = user.getPhone();
    if (phone == null
        || !tokens.verifyCode(userId, Purpose.PHONE_VERIFY, phone, code, MAX_ATTEMPTS)) {
      throw new AuthException(AuthErrorCode.AUTH_OTP_INVALID, "Phone verification code rejected");
    }
    try {
      tx.executeWithoutResult(
          status -> {
            if (users.existsByPhoneAndPhoneVerifiedTrueAndIdNot(phone, userId)) {
              throw refusedElsewhere(userId);
            }
            users.findById(userId).orElseThrow().markPhoneVerified();
          });
    } catch (DataIntegrityViolationException e) {
      throw refusedElsewhere(userId);
    }
    log.info("Phone verified for userId={}", userId);
  }

  /** Same exception as a wrong code, so a lost race or guessed decoy code reveals nothing. */
  private static AuthException refusedElsewhere(UUID userId) {
    log.info("phone verify refused: verified elsewhere userId={}", userId);
    return new AuthException(AuthErrorCode.AUTH_OTP_INVALID, "Phone verification code rejected");
  }
}
