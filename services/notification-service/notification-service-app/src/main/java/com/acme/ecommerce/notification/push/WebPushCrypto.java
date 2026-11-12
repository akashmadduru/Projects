package com.acme.ecommerce.notification.push;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * JDK-only Web Push primitives: RFC 8291 aes128gcm payload encryption and RFC 8292 VAPID (ES256)
 * JWTs. No third-party dependency (the usual library drags netty/async-http-client).
 */
public final class WebPushCrypto {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final ECParameterSpec P256 = p256();

  private WebPushCrypto() {}

  public record KeyMaterial(String publicKey, String privateKey) {}

  public static KeyMaterial generateVapidKeys() throws GeneralSecurityException {
    KeyPair kp = newKeyPair();
    byte[] priv = new byte[32];
    copy(((ECPrivateKey) kp.getPrivate()).getS(), priv, 0, 32);
    return new KeyMaterial(b64(uncompressed((ECPublicKey) kp.getPublic())), b64(priv));
  }

  public static String b64(byte[] bytes) {
    return B64.encodeToString(bytes);
  }

  public static byte[] unb64(String s) {
    return Base64.getUrlDecoder().decode(s);
  }

  /** VAPID JWT for the endpoint's origin, valid 12 h. */
  public static String vapidJwt(String audience, String subject, String privateKeyB64)
      throws GeneralSecurityException {
    String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
    long exp = System.currentTimeMillis() / 1000 + 12 * 3600;
    String claims =
        b64(
            ("{\"aud\":\"" + audience + "\",\"exp\":" + exp + ",\"sub\":\"" + subject + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    String input = header + "." + claims;
    PrivateKey key =
        KeyFactory.getInstance("EC")
            .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, unb64(privateKeyB64)), P256));
    Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
    sig.initSign(key);
    sig.update(input.getBytes(StandardCharsets.UTF_8));
    return input + "." + b64(sig.sign());
  }

  /** RFC 8291 single-record aes128gcm body. */
  public static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret)
      throws GeneralSecurityException {
    KeyPair as = newKeyPair();
    byte[] asPublic = uncompressed((ECPublicKey) as.getPublic());
    KeyAgreement ka = KeyAgreement.getInstance("ECDH");
    ka.init(as.getPrivate());
    ka.doPhase(publicKey(uaPublic), true);
    byte[] secret = ka.generateSecret();

    byte[] prkKey = hmac(authSecret, secret);
    byte[] keyInfo =
        concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
    byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));

    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    byte[] prk = hmac(salt, ikm);
    byte[] cekInfo = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    byte[] nonceInfo = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);
    byte[] cek = Arrays.copyOf(hmac(prk, concat(cekInfo, new byte[] {1})), 16);
    byte[] nonce = Arrays.copyOf(hmac(prk, concat(nonceInfo, new byte[] {1})), 12);

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
    byte[] ciphertext = cipher.doFinal(concat(payload, new byte[] {2}));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(salt);
    out.writeBytes(new byte[] {0, 0, 0x10, 0}); // record size 4096
    out.write(asPublic.length);
    out.writeBytes(asPublic);
    out.writeBytes(ciphertext);
    return out.toByteArray();
  }

  private static KeyPair newKeyPair() throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }

  private static PublicKey publicKey(byte[] raw) throws GeneralSecurityException {
    if (raw.length != 65 || raw[0] != 4) {
      throw new GeneralSecurityException("Invalid P-256 public key");
    }
    BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
    BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
    return KeyFactory.getInstance("EC")
        .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
  }

  private static byte[] uncompressed(ECPublicKey key) {
    byte[] out = new byte[65];
    out[0] = 4;
    copy(key.getW().getAffineX(), out, 1, 32);
    copy(key.getW().getAffineY(), out, 33, 32);
    return out;
  }

  private static void copy(BigInteger v, byte[] dst, int off, int len) {
    byte[] b = v.toByteArray();
    int n = Math.min(len, b.length);
    System.arraycopy(b, b.length - n, dst, off + len - n, n);
  }

  private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data);
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }

  private static ECParameterSpec p256() {
    try {
      AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
      ap.init(new ECGenParameterSpec("secp256r1"));
      return ap.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
