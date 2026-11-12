package com.acme.ecommerce.seller.crypto;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM for PII at rest (bank account numbers). Output: base64(iv || ciphertext+tag). */
@Component
public class DataCipher {

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public DataCipher(@Value("${seller.data-key:}") String base64Key) {
    byte[] raw = base64Key == null || base64Key.isBlank() ? new byte[0] : Base64.getDecoder().decode(base64Key.trim());
    if (raw.length != 32) {
      throw new IllegalStateException("SELLER_DATA_KEY must be base64 of exactly 32 bytes");
    }
    this.key = new SecretKeySpec(raw, "AES");
  }

  public String encrypt(String plain) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Encryption failed", e);
    }
  }

  public String decrypt(String token) {
    try {
      byte[] all = Base64.getDecoder().decode(token);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
      return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Decryption failed", e);
    }
  }
}
