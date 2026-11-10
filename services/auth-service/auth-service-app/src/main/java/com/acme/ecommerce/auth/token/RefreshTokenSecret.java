package com.acme.ecommerce.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and hashing of the opaque refresh token.
 *
 * <p>PACKAGE-PRIVATE: nothing outside the token package has a legitimate reason to hash a refresh
 * token, and anything that thinks it does is about to build a second lookup path around {@link
 * RefreshTokenService}.
 *
 * <p>WHY SHA-256 AND NOT ARGON2/BCRYPT. Password hashing needs a slow KDF because the input is a
 * human-chosen string from a small, guessable space - the KDF's whole job is to make each guess
 * expensive. This input is 256 bits of {@link SecureRandom} output. There is no dictionary, no
 * rainbow table, and no feasible brute force; a slow KDF would add nothing except ~100ms to the
 * hottest authenticated path in the platform. A salt would additionally break the unique-index
 * lookup that makes refresh O(1) - with a per-row salt you cannot find the row without scanning
 * every row and hashing each one.
 */
final class RefreshTokenSecret {

  /**
   * 32 bytes = 256 bits. Well past the point where guessing is the attacker's best option, and it
   * encodes to a 43-character cookie value.
   */
  private static final int TOKEN_BYTES = 32;

  /**
   * One shared instance. {@code SecureRandom} is thread-safe, and constructing one per call can
   * block on entropy.
   */
  private static final SecureRandom RANDOM = new SecureRandom();

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private RefreshTokenSecret() {
    throw new AssertionError("static utility");
  }

  /** Mints a fresh opaque token. This value exists in memory and in one cookie; nowhere else. */
  static String generate() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return ENCODER.encodeToString(bytes);
  }

  /** SHA-256 hex of the raw token: exactly what {@code refresh_tokens.token_hash} stores. */
  static String hash(String rawToken) {
    try {
      // A fresh MessageDigest per call: the class is NOT thread-safe, and with
      // virtual threads there can be tens of thousands of concurrent callers.
      // Allocating one is nanoseconds.
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandatory in every conformant JRE.
      throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
    }
  }
}
