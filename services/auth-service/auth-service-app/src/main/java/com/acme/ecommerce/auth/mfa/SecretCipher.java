package com.acme.ecommerce.auth.mfa;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM for TOTP secrets at rest. Key: {@code AUTH_DATA_KEY} (base64, 32 bytes). */
@Component
public class SecretCipher {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretKeySpec key;

  public SecretCipher(@Value("${auth.data-key:}") String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      this.key = null;
    } else {
      byte[] raw = Base64.getDecoder().decode(base64Key.trim());
      if (raw.length != 32) {
        throw new IllegalStateException("AUTH_DATA_KEY must be 32 bytes, base64-encoded");
      }
      this.key = new SecretKeySpec(raw, "AES");
    }
  }

  public String encrypt(byte[] plain) {
    requireKey();
    try {
      byte[] iv = new byte[12];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] ct = cipher.doFinal(plain);
      return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Encryption failed", e);
    }
  }

  public byte[] decrypt(String encoded) {
    requireKey();
    try {
      byte[] all = Base64.getDecoder().decode(encoded);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
      return cipher.doFinal(all, 12, all.length - 12);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Decryption failed", e);
    }
  }

  private void requireKey() {
    if (key == null) {
      throw new IllegalStateException("AUTH_DATA_KEY is not configured; 2FA is unavailable");
    }
  }
}
