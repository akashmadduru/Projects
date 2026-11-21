package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.service.OneTimeTokenService.Purpose;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import com.acme.ecommerce.auth.token.DeviceContext;
import com.acme.ecommerce.auth.token.TokenService;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Passwordless login by SMS code: front door number three into {@link TokenService}.
 *
 * <p>NO ENUMERATION: {@link #request} returns normally for unknown / unverified numbers and the SMS
 * is queued asynchronously. {@link #verify} answers every failure (unknown number, wrong, expired
 * or exhausted code) with the same {@code AUTH_OTP_INVALID}. Only a phone that was VERIFIED by its
 * owner can receive a login code, so typing someone else's number into the add-phone form never
 * grants anything. Not {@code @Transactional}, for the same attempt-counter reason as phone
 * verification.
 */
@Service
public class OtpLoginService {

  private static final Logger log = LoggerFactory.getLogger(OtpLoginService.class);
  private static final Duration CODE_TTL = Duration.ofMinutes(5);

  private final UserAccountRepository users;
  private final OneTimeTokenService tokens;
  private final NotificationGateway notifications;
  private final UserPrincipalService principals;
  private final TwoFactorService twoFactor;
  private final AuthMetrics metrics;

  /** Tokens are issued (or a second factor demanded) by {@link TwoFactorService}. */
  public OtpLoginService(
      UserAccountRepository users,
      OneTimeTokenService tokens,
      NotificationGateway notifications,
      UserPrincipalService principals,
      AuthMetrics metrics,
      TwoFactorService twoFactor) {
    this.users = users;
    this.tokens = tokens;
    this.notifications = notifications;
    this.principals = principals;
    this.metrics = metrics;
    this.twoFactor = twoFactor;
  }

  /** Sends a login code if (and only if) a verified account owns the number. Never throws. */
  public void request(String rawPhone) {
    try {
      Optional<String> phone = PhoneNumbers.normalise(rawPhone);
      if (phone.isEmpty()) {
        return;
      }
      users
          .findWithRolesByPhoneAndPhoneVerifiedTrue(phone.get())
          .filter(UserAccount::canAuthenticate)
          .ifPresent(
              user ->
                  tokens
                      .issueCode(
                          user.getId(),
                          Purpose.OTP_LOGIN,
                          phone.get(),
                          CODE_TTL,
                          PhoneVerificationService.RESEND_COOLDOWN)
                      .ifPresent(
                          code ->
                              notifications.sendSms(
                                  phone.get(),
                                  "Your login code is " + code + ". It expires in 5 minutes.")));
    } catch (RuntimeException e) {
      log.warn("OTP request failed: {}", e.toString());
    }
  }

  /** Verifies the code and issues a fresh token family (same as password login). */
  public LoginResult verify(String rawPhone, String code, DeviceContext device) {
    String phone = PhoneNumbers.normalise(rawPhone).orElse(null);
    UserAccount user =
        phone == null ? null : users.findWithRolesByPhoneAndPhoneVerifiedTrue(phone).orElse(null);
    if (user == null
        || !tokens.verifyCode(
            user.getId(), Purpose.OTP_LOGIN, phone, code, PhoneVerificationService.MAX_ATTEMPTS)) {
      metrics.loginAttempt(AuthMethod.OTP, "bad_code");
      throw new AuthException(AuthErrorCode.AUTH_OTP_INVALID, "OTP rejected");
    }
    if (!user.canAuthenticate()) {
      metrics.loginAttempt(AuthMethod.OTP, "disabled");
      throw new AuthException(AuthErrorCode.AUTH_ACCOUNT_DISABLED, "OTP login refused: disabled");
    }
    metrics.loginAttempt(AuthMethod.OTP, "success");
    AuthenticatedPrincipal principal = principals.principalFor(user, AuthMethod.OTP);
    // THE CONVERGENCE POINT. familyId == null starts a new family.
    return twoFactor.completeLogin(principal, device);
  }
}
