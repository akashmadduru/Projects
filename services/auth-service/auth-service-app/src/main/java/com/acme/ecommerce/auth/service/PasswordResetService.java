package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.domain.UserCredential;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.repository.UserCredentialRepository;
import com.acme.ecommerce.auth.service.OneTimeTokenService.Purpose;
import com.acme.ecommerce.auth.token.RevokeReason;
import com.acme.ecommerce.auth.token.TokenService;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.notification.api.dto.EmailTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Forgot / reset password.
 *
 * <p>NO ACCOUNT ENUMERATION: {@link #requestReset} returns normally whether or not the address
 * exists, and the email send is asynchronous, so neither the response nor its timing differs. A
 * reset also proves control of the mailbox, so it marks the email verified, and it revokes EVERY
 * refresh token of the user (a stolen session must not survive a password reset).
 */
@Service
public class PasswordResetService {

  private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
  private static final Duration TTL = Duration.ofMinutes(30);

  private final UserAccountRepository users;
  private final UserCredentialRepository credentials;
  private final OneTimeTokenService tokens;
  private final NotificationGateway notifications;
  private final PasswordEncoder passwordEncoder;
  private final TokenService tokenService;
  private final TimeProvider timeProvider;
  private final TransactionTemplate tx;
  private final String storefrontBaseUrl;

  public PasswordResetService(
      UserAccountRepository users,
      UserCredentialRepository credentials,
      OneTimeTokenService tokens,
      NotificationGateway notifications,
      PasswordEncoder passwordEncoder,
      TokenService tokenService,
      TimeProvider timeProvider,
      TransactionTemplate tx,
      AuthProperties properties) {
    this.users = users;
    this.credentials = credentials;
    this.tokens = tokens;
    this.notifications = notifications;
    this.passwordEncoder = passwordEncoder;
    this.tokenService = tokenService;
    this.timeProvider = timeProvider;
    this.tx = tx;
    this.storefrontBaseUrl = properties.storefront().baseUrl();
  }

  /** Starts a reset. Always returns normally. */
  public void requestReset(String rawEmail) {
    try {
      String email = UserAccount.normaliseEmail(rawEmail);
      users
          .findByEmail(email)
          .filter(UserAccount::canAuthenticate)
          .ifPresent(
              user -> {
                String token = tokens.issueLinkToken(user.getId(), Purpose.PASSWORD_RESET, TTL);
                notifications.sendEmail(
                    user.getEmail(),
                    EmailTemplate.PASSWORD_RESET,
                    Map.of(
                        "link", storefrontBaseUrl + "/reset-password?token=" + token,
                        "name", user.getDisplayName()));
              });
    } catch (RuntimeException e) {
      // Swallowed deliberately: an error here must look identical to "no such account".
      log.warn("Password reset request failed: {}", e.toString());
    }
  }

  /** Completes a reset: sets the password, clears lockout, revokes all sessions. */
  public void reset(String rawToken, String newPassword) {
    UUID userId =
        tokens
            .consumeLinkToken(rawToken, Purpose.PASSWORD_RESET)
            .orElseThrow(
                () ->
                    new AuthException(
                        AuthErrorCode.AUTH_RESET_TOKEN_INVALID,
                        "Password reset token unknown, expired or used"));
    String encoded = passwordEncoder.encode(newPassword);
    tx.executeWithoutResult(
        status -> {
          Instant now = timeProvider.now();
          credentials
              .findById(userId)
              .ifPresentOrElse(
                  credential -> credential.changePassword(encoded, now),
                  () -> credentials.save(UserCredential.create(userId, encoded, now)));
          users.findById(userId).ifPresent(UserAccount::markEmailVerified);
        });
    tokenService.revokeAllSessions(userId, RevokeReason.PASSWORD_CHANGE);
    log.info("Password reset for userId={}", userId);
  }
}
