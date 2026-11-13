package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.cache.UserSnapshotFactory;
import com.acme.ecommerce.auth.domain.MfaChallenge;
import com.acme.ecommerce.auth.domain.RecoveryCode;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.domain.UserTotp;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.auth.mfa.SecretCipher;
import com.acme.ecommerce.auth.mfa.Totp;
import com.acme.ecommerce.auth.repository.MfaChallengeRepository;
import com.acme.ecommerce.auth.repository.RecoveryCodeRepository;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.repository.UserCredentialRepository;
import com.acme.ecommerce.auth.repository.UserTotpRepository;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import com.acme.ecommerce.auth.token.DeviceContext;
import com.acme.ecommerce.auth.token.TokenPair;
import com.acme.ecommerce.auth.token.TokenService;
import com.acme.ecommerce.common.core.time.TimeProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TOTP two-factor auth: enrolment, the MFA login step and recovery codes.
 *
 * <p>Every first-factor success (password, OTP, Google) funnels through {@link #completeLogin}: a
 * 2FA user gets a single-use, 5-minute, 5-attempt mfaToken and NO refresh token until {@link
 * #verifyLogin} succeeds.
 */
@Service
public class TwoFactorService {

  private static final Logger log = LoggerFactory.getLogger(TwoFactorService.class);
  public static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
  private static final int MAX_ATTEMPTS = 5;
  private static final int RECOVERY_CODES = 10;
  private static final String ISSUER = "Acme Store";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserTotpRepository totps;
  private final RecoveryCodeRepository recoveryCodes;
  private final MfaChallengeRepository challenges;
  private final UserAccountRepository users;
  private final UserCredentialRepository credentials;
  private final PasswordEncoder passwordEncoder;
  private final SecretCipher cipher;
  private final TokenService tokenService;
  private final UserPrincipalService principals;
  private final UserSnapshotFactory snapshots;
  private final TimeProvider time;
  private final AuthMetrics metrics;

  public TwoFactorService(
      UserTotpRepository totps,
      RecoveryCodeRepository recoveryCodes,
      MfaChallengeRepository challenges,
      UserAccountRepository users,
      UserCredentialRepository credentials,
      PasswordEncoder passwordEncoder,
      SecretCipher cipher,
      TokenService tokenService,
      UserPrincipalService principals,
      UserSnapshotFactory snapshots,
      TimeProvider time,
      AuthMetrics metrics) {
    this.totps = totps;
    this.recoveryCodes = recoveryCodes;
    this.challenges = challenges;
    this.users = users;
    this.credentials = credentials;
    this.passwordEncoder = passwordEncoder;
    this.cipher = cipher;
    this.tokenService = tokenService;
    this.principals = principals;
    this.snapshots = snapshots;
    this.time = time;
    this.metrics = metrics;
  }

  // ---- login step ----------------------------------------------------------------------------

  /** Issues tokens, or a pending MFA challenge when the user has 2FA enabled. */
  public LoginResult completeLogin(AuthenticatedPrincipal principal, DeviceContext device) {
    if (totps.isEnabled(principal.userId())) {
      String raw = randomToken();
      Instant now = time.now();
      challenges.save(
          new MfaChallenge(
              sha256(raw),
              principal.userId(),
              principal.authMethod().claimValue(),
              now,
              now.plus(CHALLENGE_TTL)));
      metrics.loginAttempt(principal.authMethod(), "mfa_required");
      return LoginResult.mfa(raw, CHALLENGE_TTL.toSeconds());
    }
    return LoginResult.issued(
        tokenService.issueTokenPair(principal, device, null, null, snapshots.snapshotFor(principal)));
  }

  /** True while the challenge exists, is unconsumed, unexpired and not attempt-locked. */
  @Transactional(readOnly = true)
  public boolean isPending(String rawMfaToken) {
    return challenges
        .findByTokenHash(sha256(rawMfaToken))
        .filter(c -> c.getConsumedAt() == null && c.getExpiresAt().isAfter(time.now()))
        .isPresent();
  }

  /** Not transactional as a whole: failed-attempt counters must survive the thrown exception. */
  public TokenPair verifyLogin(
      String rawMfaToken, String code, String recoveryCode, DeviceContext device) {
    Instant now = time.now();
    MfaChallenge challenge =
        challenges
            .findByTokenHash(sha256(rawMfaToken == null ? "" : rawMfaToken))
            .filter(c -> c.getConsumedAt() == null && c.getExpiresAt().isAfter(now))
            .orElseThrow(
                () ->
                    new AuthException(
                        AuthErrorCode.AUTH_MFA_TOKEN_INVALID, "MFA token unknown, used or expired"));
    if (challenge.getAttempts() >= MAX_ATTEMPTS) {
      throw new AuthException(AuthErrorCode.AUTH_MFA_LOCKED, "MFA challenge locked");
    }
    UUID userId = challenge.getUserId();
    UserTotp totp =
        totps
            .findByUser(userId)
            .filter(UserTotp::isEnabled)
            .orElseThrow(
                () ->
                    new AuthException(
                        AuthErrorCode.AUTH_MFA_TOKEN_INVALID, "2FA no longer enabled"));

    if (!checkSecondFactor(userId, totp, code, recoveryCode, now)) {
      challenges.spendAttempt(challenge.getId());
      metrics.loginAttempt(AuthMethod.fromClaimValue(challenge.getAuthMethod()), "mfa_bad_code");
      throw new AuthException(AuthErrorCode.AUTH_MFA_INVALID, "Second factor rejected");
    }
    if (challenges.consume(challenge.getId(), now) != 1) {
      throw new AuthException(AuthErrorCode.AUTH_MFA_TOKEN_INVALID, "MFA token already used");
    }
    UserAccount user =
        users
            .findWithRolesById(userId)
            .orElseThrow(
                () -> new AuthException(AuthErrorCode.AUTH_MFA_TOKEN_INVALID, "User gone"));
    if (!user.canAuthenticate()) {
      throw new AuthException(AuthErrorCode.AUTH_ACCOUNT_DISABLED, "MFA login refused: disabled");
    }
    AuthMethod method = AuthMethod.fromClaimValue(challenge.getAuthMethod());
    metrics.loginAttempt(method, "success");
    AuthenticatedPrincipal principal = principals.principalFor(user, method);
    return tokenService.issueTokenPair(
        principal, device, null, null, snapshots.snapshotFor(principal));
  }

  // ---- enrolment -----------------------------------------------------------------------------

  public record Setup(String otpauthUrl, String secretBase32) {}

  @Transactional
  public Setup setup(UUID userId, String email) {
    UserTotp existing = totps.findByUser(userId).orElse(null);
    if (existing != null && existing.isEnabled()) {
      throw new AuthException(AuthErrorCode.AUTH_2FA_STATE, "2FA is already enabled");
    }
    totps.deleteForUser(userId);
    byte[] secret = Totp.newSecret();
    totps.save(new UserTotp(userId, cipher.encrypt(secret), time.now()));
    String b32 = Totp.base32(secret);
    String label = ISSUER + ":" + email;
    String url =
        "otpauth://totp/"
            + java.net.URLEncoder.encode(label, StandardCharsets.UTF_8).replace("+", "%20")
            + "?secret="
            + b32
            + "&issuer="
            + java.net.URLEncoder.encode(ISSUER, StandardCharsets.UTF_8).replace("+", "%20")
            + "&algorithm=SHA1&digits=6&period=30";
    return new Setup(url, b32);
  }

  @Transactional
  public List<String> enable(UUID userId, String code) {
    UserTotp totp =
        totps
            .findByUser(userId)
            .orElseThrow(
                () -> new AuthException(AuthErrorCode.AUTH_2FA_STATE, "Run 2FA setup first"));
    if (totp.isEnabled()) {
      throw new AuthException(AuthErrorCode.AUTH_2FA_STATE, "2FA is already enabled");
    }
    Instant now = time.now();
    long step =
        Totp.match(
            cipher.decrypt(totp.getSecretEnc()), code, Totp.stepAt(now.getEpochSecond()), 0);
    if (step < 0) {
      throw new AuthException(AuthErrorCode.AUTH_MFA_INVALID, "Code rejected");
    }
    totp.markEnabled(step, now);
    recoveryCodes.deleteForUser(userId);
    List<String> plain = new ArrayList<>();
    for (int i = 0; i < RECOVERY_CODES; i++) {
      byte[] raw = new byte[7];
      RANDOM.nextBytes(raw);
      String c = Totp.base32(raw).substring(0, 10);
      plain.add(c.substring(0, 5) + "-" + c.substring(5));
      recoveryCodes.save(new RecoveryCode(userId, recoveryHash(userId, c), now));
    }
    log.info("2FA enabled for userId={}", userId);
    return plain;
  }

  @Transactional
  public void disable(UUID userId, String code, String recoveryCode, String password) {
    UserTotp totp =
        totps
            .findByUser(userId)
            .filter(UserTotp::isEnabled)
            .orElseThrow(() -> new AuthException(AuthErrorCode.AUTH_2FA_STATE, "2FA is not enabled"));
    // Password first (when the account has one): a stolen access token alone must not suffice.
    var credential = credentials.findById(userId);
    if (credential.isPresent()
        && (password == null
            || !passwordEncoder.matches(password, credential.get().getPasswordHash()))) {
      throw new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS, "Password rejected");
    }
    if (!checkSecondFactor(userId, totp, code, recoveryCode, time.now())) {
      throw new AuthException(AuthErrorCode.AUTH_MFA_INVALID, "Second factor rejected");
    }
    totps.deleteForUser(userId);
    recoveryCodes.deleteForUser(userId);
    log.info("2FA disabled for userId={}", userId);
  }

  @Transactional(readOnly = true)
  public boolean isEnabled(UUID userId) {
    return totps.isEnabled(userId);
  }

  @Transactional(readOnly = true)
  public long recoveryCodesRemaining(UUID userId) {
    return recoveryCodes.countUnused(userId);
  }

  // ---- helpers -------------------------------------------------------------------------------

  private boolean checkSecondFactor(
      UUID userId, UserTotp totp, String code, String recoveryCode, Instant now) {
    if (code != null && !code.isBlank()) {
      long step =
          Totp.match(
              cipher.decrypt(totp.getSecretEnc()),
              code.trim(),
              Totp.stepAt(now.getEpochSecond()),
              totp.getLastUsedStep());
      return step >= 0 && totps.advanceStep(userId, step) == 1;
    }
    if (recoveryCode != null && !recoveryCode.isBlank()) {
      return recoveryCodes.spend(userId, recoveryHash(userId, recoveryCode), now) == 1;
    }
    return false;
  }

  private static String recoveryHash(UUID userId, String code) {
    String normalised = code.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    return sha256(userId + ":" + normalised);
  }

  private static String randomToken() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
