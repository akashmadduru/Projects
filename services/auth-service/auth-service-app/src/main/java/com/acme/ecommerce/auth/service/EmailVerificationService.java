package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.service.OneTimeTokenService.Purpose;
import com.acme.ecommerce.notification.api.dto.EmailTemplate;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Email-address verification. Login is NOT blocked for unverified addresses (tech debt: policy
 * decision). Deliberately not {@code @Transactional}: token redemption and the user update commit
 * independently, and a failed redemption must throw without rolling anything back.
 */
@Service
public class EmailVerificationService {

  private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
  private static final Duration TTL = Duration.ofHours(24);

  private final OneTimeTokenService tokens;
  private final UserAccountRepository users;
  private final NotificationGateway notifications;
  private final TransactionTemplate tx;
  private final String storefrontBaseUrl;

  public EmailVerificationService(
      OneTimeTokenService tokens,
      UserAccountRepository users,
      NotificationGateway notifications,
      TransactionTemplate tx,
      AuthProperties properties) {
    this.tokens = tokens;
    this.users = users;
    this.notifications = notifications;
    this.tx = tx;
    this.storefrontBaseUrl = properties.storefront().baseUrl();
  }

  /** Issues a token and queues the email. Never throws: callers (register) must not fail on it. */
  public void sendVerification(UUID userId, String email, String displayName) {
    try {
      String token = tokens.issueLinkToken(userId, Purpose.EMAIL_VERIFY, TTL);
      notifications.sendEmail(
          email,
          EmailTemplate.EMAIL_VERIFICATION,
          Map.of(
              "link", storefrontBaseUrl + "/verify-email?token=" + token,
              "name", displayName == null ? "there" : displayName));
    } catch (RuntimeException e) {
      log.warn("Could not queue verification email for userId={}: {}", userId, e.toString());
    }
  }

  /** Redeems a verification token. */
  public void verify(String rawToken) {
    UUID userId =
        tokens
            .consumeLinkToken(rawToken, Purpose.EMAIL_VERIFY)
            .orElseThrow(
                () ->
                    new AuthException(
                        AuthErrorCode.AUTH_VERIFY_TOKEN_INVALID,
                        "Email verification token unknown, expired or used"));
    tx.executeWithoutResult(
        status -> users.findById(userId).ifPresent(UserAccount::markEmailVerified));
    log.info("Email verified for userId={}", userId);
  }

  /** Re-sends the verification email for the signed-in user (no-op when already verified). */
  public void resend(UUID userId) {
    Optional<UserAccount> user = users.findById(userId);
    if (user.isPresent() && !user.get().isEmailVerified()) {
      sendVerification(userId, user.get().getEmail(), user.get().getDisplayName());
    }
  }
}
