package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.domain.OneTimeToken;
import com.acme.ecommerce.auth.repository.OneTimeTokenRepository;
import com.acme.ecommerce.common.core.time.TimeProvider;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and redeems every single-use secret in the service: link tokens (password reset, email
 * verification) and 6-digit codes (phone verification, OTP login).
 *
 * <p>Only hashes are stored. Link tokens are 256 bits of CSPRNG output, so an unsalted SHA-256 is
 * appropriate (same reasoning as refresh tokens). Codes only have 10^6 values, so they are hashed
 * with HMAC-SHA256 under a server key over {@code userId:purpose:code}; online guessing is stopped
 * by the short expiry plus the hard attempt cap. Redemption is atomic (conditional UPDATE), so a
 * token or code works exactly once even under concurrent requests.
 */
@Service
public class OneTimeTokenService {

  /** What a stored secret is for. Stored by name. */
  public enum Purpose {
    PASSWORD_RESET,
    EMAIL_VERIFY,
    PHONE_VERIFY,
    OTP_LOGIN
  }

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Logger log = LoggerFactory.getLogger(OneTimeTokenService.class);

  private final OneTimeTokenRepository tokens;
  private final TimeProvider timeProvider;

  /**
   * HMAC key for 6-digit codes (TD-P6-09), or null when {@code AUTH_DATA_KEY} is not set. A code
   * has only 10^6 values, so a plain hash in a leaked database is reversed in milliseconds; with a
   * key the attacker also needs the server secret. Derived from {@code AUTH_DATA_KEY} with a fixed
   * label so no second secret has to be managed. Changing the key only invalidates codes in flight
   * (they live minutes).
   */
  private final SecretKeySpec codeKey;

  public OneTimeTokenService(
      OneTimeTokenRepository tokens,
      TimeProvider timeProvider,
      @Value("${auth.data-key:}") String dataKey) {
    this.tokens = tokens;
    this.timeProvider = timeProvider;
    this.codeKey = deriveCodeKey(dataKey);
    if (codeKey == null) {
      log.warn(
          "AUTH_DATA_KEY is not set: one-time codes are hashed without a server secret."
              + " Set it in every non-local environment.");
    }
  }

  /** Issues a link token, replacing any previous one for the same user and purpose. */
  @Transactional
  public String issueLinkToken(UUID userId, Purpose purpose, Duration ttl) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = timeProvider.now();
    tokens.deleteForUser(userId, purpose.name());
    tokens.save(new OneTimeToken(userId, purpose.name(), sha256(raw), null, now, now.plus(ttl)));
    return raw;
  }

  /** Redeems a link token. Returns the owning user id, or empty if unknown/expired/used. */
  @Transactional
  public Optional<UUID> consumeLinkToken(String raw, Purpose purpose) {
    if (raw == null || raw.isBlank() || raw.length() > 200) {
      return Optional.empty();
    }
    return tokens
        .findByPurposeAndTokenHash(purpose.name(), sha256(raw.trim()))
        .filter(t -> tokens.consume(t.getId(), timeProvider.now()) == 1)
        .map(OneTimeToken::getUserId);
  }

  /**
   * Issues a fresh 6-digit code, replacing the previous one.
   *
   * @return the raw code, or empty when the resend cooldown for this user and purpose is active
   */
  @Transactional
  public Optional<String> issueCode(
      UUID userId, Purpose purpose, String target, Duration ttl, Duration cooldown) {
    Instant now = timeProvider.now();
    Optional<OneTimeToken> latest =
        tokens.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose.name());
    if (latest.isPresent() && latest.get().getCreatedAt().plus(cooldown).isAfter(now)) {
      return Optional.empty();
    }
    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
    tokens.deleteForUser(userId, purpose.name());
    tokens.save(
        new OneTimeToken(
            userId, purpose.name(), codeHash(userId, purpose, code), target, now, now.plus(ttl)));
    return Optional.of(code);
  }

  /**
   * Checks a code. Every call spends one attempt (atomically capped at {@code maxAttempts}); a
   * correct code is consumed. Never throws on a bad code so the attempt counter always commits.
   *
   * @param expectedTarget if non-null the stored target must equal it (phone-change safety)
   */
  @Transactional
  public boolean verifyCode(
      UUID userId, Purpose purpose, String expectedTarget, String code, int maxAttempts) {
    Instant now = timeProvider.now();
    Optional<OneTimeToken> latest =
        tokens.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose.name());
    if (latest.isEmpty()) {
      return false;
    }
    OneTimeToken token = latest.get();
    if (token.getConsumedAt() != null || !token.getExpiresAt().isAfter(now)) {
      return false;
    }
    if (expectedTarget != null && !expectedTarget.equals(token.getTarget())) {
      return false;
    }
    if (tokens.spendAttempt(token.getId(), maxAttempts) != 1) {
      return false;
    }
    boolean match =
        MessageDigest.isEqual(
            token.getTokenHash().getBytes(StandardCharsets.UTF_8),
            codeHash(userId, purpose, code).getBytes(StandardCharsets.UTF_8));
    return match && tokens.consume(token.getId(), now) == 1;
  }

  /** Housekeeping: drops rows that expired before the cutoff. */
  @Transactional
  public int purgeExpired(Instant cutoff) {
    return tokens.deleteExpiredBefore(cutoff);
  }

  private String codeHash(UUID userId, Purpose purpose, String code) {
    String input = userId + ":" + purpose.name() + ":" + code;
    if (codeKey == null) {
      return sha256(input);
    }
    return HexFormat.of().formatHex(hmac(codeKey, input.getBytes(StandardCharsets.UTF_8)));
  }

  static SecretKeySpec deriveCodeKey(String base64DataKey) {
    if (base64DataKey == null || base64DataKey.isBlank()) {
      return null;
    }
    byte[] master = Base64.getDecoder().decode(base64DataKey.trim());
    byte[] derived =
        hmac(
            new SecretKeySpec(master, "HmacSHA256"),
            "acme-auth/one-time-code-hash/v1".getBytes(StandardCharsets.UTF_8));
    return new SecretKeySpec(derived, "HmacSHA256");
  }

  private static byte[] hmac(SecretKeySpec key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
