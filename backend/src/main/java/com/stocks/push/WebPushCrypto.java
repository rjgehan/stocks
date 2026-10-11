package com.stocks.push;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push message encryption (§19.2): RFC 8291 ({@code aes128gcm} content coding of RFC 8188 with the Web Push key
 * derivation) on the JDK 21 providers only: ECDH on P-256, HKDF-SHA-256 (RFC 5869, on {@code HmacSHA256}) and
 * AES-128-GCM. Also the P-256 key encodings the protocol uses (65-byte uncompressed points, 32-byte scalars,
 * base64url). Verified against the RFC 8291 §5 / Appendix A test vector ({@code WebPushCryptoTest}).
 *
 * <p>One record per message: the plaintext plus the {@code 0x02} last-record delimiter must fit in
 * {@link #RECORD_SIZE} − 16 (the GCM tag), which every payload of ours does by a wide margin ({@link #MAX_PLAINTEXT}).
 */
public final class WebPushCrypto {

  /** The {@code rs} header field: 4096, the size every push service accepts. */
  public static final int RECORD_SIZE = 4096;
  /** Largest plaintext of one record: rs − 16 (tag) − 1 (delimiter); push services cap the body near 4 KB anyway. */
  public static final int MAX_PLAINTEXT = RECORD_SIZE - 16 - 1;
  static final int POINT_LENGTH = 65;
  static final int AUTH_LENGTH = 16;
  static final int SALT_LENGTH = 16;

  private static final byte[] KEY_INFO = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final ECParameterSpec P256 = p256Params();

  private WebPushCrypto() {}

  // ------------------------------------------------------------------------------------------- keys

  /** The NIST P-256 (secp256r1) domain parameters. */
  public static ECParameterSpec p256() {
    return P256;
  }

  private static ECParameterSpec p256Params() {
    try {
      AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
      params.init(new ECGenParameterSpec("secp256r1"));
      return params.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("P-256 is not available in this JDK", e);
    }
  }

  /** A fresh P-256 key pair (VAPID keys, and the per-message application-server key). */
  public static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
      return g.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot generate a P-256 key pair", e);
    }
  }

  /** The 65-byte uncompressed form {@code 0x04 ‖ X ‖ Y} (X9.62), as browsers and RFC 8291 use it. */
  public static byte[] encodePoint(ECPublicKey key) {
    byte[] out = new byte[POINT_LENGTH];
    out[0] = 0x04;
    copyFixed(key.getW().getAffineX(), out, 1);
    copyFixed(key.getW().getAffineY(), out, 33);
    return out;
  }

  /**
   * Parses a 65-byte uncompressed P-256 point and checks that it lies on the curve (an invalid point would otherwise
   * reach ECDH); {@link IllegalArgumentException} for anything else.
   */
  public static ECPublicKey decodePoint(byte[] uncompressed) {
    if (uncompressed == null || uncompressed.length != POINT_LENGTH || uncompressed[0] != 0x04) {
      throw new IllegalArgumentException("not a 65-byte uncompressed P-256 point");
    }
    BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
    BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
    if (!onCurve(x, y)) {
      throw new IllegalArgumentException("the point is not on P-256");
    }
    try {
      return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("not a P-256 public key", e);
    }
  }

  /** y² ≡ x³ + ax + b (mod p), with both coordinates in [0, p). */
  static boolean onCurve(BigInteger x, BigInteger y) {
    EllipticCurve curve = P256.getCurve();
    BigInteger p = ((ECFieldFp) curve.getField()).getP();
    if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
      return false;
    }
    BigInteger left = y.multiply(y).mod(p);
    BigInteger right = x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
    return left.equals(right);
  }

  /** The 32-byte big-endian private scalar. */
  public static byte[] encodeScalar(ECPrivateKey key) {
    byte[] out = new byte[32];
    copyFixed(key.getS(), out, 0);
    return out;
  }

  /** A P-256 private key from its 32-byte scalar. */
  public static ECPrivateKey decodeScalar(byte[] scalar) {
    if (scalar == null || scalar.length != 32) {
      throw new IllegalArgumentException("not a 32-byte P-256 private key");
    }
    BigInteger s = new BigInteger(1, scalar);
    if (s.signum() == 0 || s.compareTo(P256.getOrder()) >= 0) {
      throw new IllegalArgumentException("the private key is out of range");
    }
    try {
      return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(s, P256));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("not a P-256 private key", e);
    }
  }

  private static void copyFixed(BigInteger value, byte[] out, int offset) {
    byte[] raw = value.toByteArray();                       // big-endian, maybe with a sign byte or shorter
    int len = Math.min(raw.length, 32);
    System.arraycopy(raw, raw.length - len, out, offset + 32 - len, len);
  }

  // ------------------------------------------------------------------------------------------- base64url

  /** base64url without padding. */
  public static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Decodes base64url, tolerating padding, whitespace and the standard alphabet ({@code +/}), which some clients
   * send; {@link IllegalArgumentException} when it is not base64 at all.
   */
  public static byte[] unb64(String text) {
    if (text == null) {
      throw new IllegalArgumentException("missing base64url value");
    }
    String clean = text.strip().replaceAll("\\s", "").replace('+', '-').replace('/', '_');
    int end = clean.length();
    while (end > 0 && clean.charAt(end - 1) == '=') {
      end--;
    }
    return Base64.getUrlDecoder().decode(clean.substring(0, end));
  }

  // ------------------------------------------------------------------------------------------- encryption

  /** Encrypts one push message for a subscription, with a fresh application-server key pair and salt. */
  public static byte[] encrypt(byte[] plaintext, ECPublicKey uaPublic, byte[] authSecret) {
    byte[] salt = new byte[SALT_LENGTH];
    RANDOM.nextBytes(salt);
    return encrypt(plaintext, uaPublic, authSecret, generateKeyPair(), salt);
  }

  /**
   * RFC 8291 §3.4 with explicit sender keys and salt (the test vector fixes them):
   * <pre>
   *   ecdh_secret = ECDH(as_private, ua_public)
   *   IKM   = HKDF(salt = auth_secret, IKM = ecdh_secret, info = "WebPush: info" ‖ 0x00 ‖ ua_public ‖ as_public, L = 32)
   *   PRK   = HKDF-Extract(salt, IKM)
   *   CEK   = HKDF-Expand(PRK, "Content-Encoding: aes128gcm" ‖ 0x00, 16)
   *   NONCE = HKDF-Expand(PRK, "Content-Encoding: nonce" ‖ 0x00, 12)
   *   body  = salt ‖ rs(uint32) ‖ idlen(65) ‖ as_public ‖ AES-128-GCM(CEK, NONCE, plaintext ‖ 0x02)
   * </pre>
   */
  public static byte[] encrypt(byte[] plaintext, ECPublicKey uaPublic, byte[] authSecret, KeyPair asKeys,
                               byte[] salt) {
    if (plaintext.length > MAX_PLAINTEXT) {
      throw new IllegalArgumentException("push payload of " + plaintext.length + " bytes is too large");
    }
    if (authSecret == null || authSecret.length != AUTH_LENGTH) {
      throw new IllegalArgumentException("auth secret must be 16 bytes");
    }
    if (salt == null || salt.length != SALT_LENGTH) {
      throw new IllegalArgumentException("salt must be 16 bytes");
    }
    byte[] uaPoint = encodePoint(uaPublic);
    byte[] asPoint = encodePoint((ECPublicKey) asKeys.getPublic());
    byte[] ecdhSecret = ecdh((ECPrivateKey) asKeys.getPrivate(), uaPublic);
    byte[] ikm = hkdf(authSecret, ecdhSecret, concat(KEY_INFO, uaPoint, asPoint), 32);
    byte[] prk = hkdfExtract(salt, ikm);
    byte[] cek = hkdfExpand(prk, CEK_INFO, 16);
    byte[] nonce = hkdfExpand(prk, NONCE_INFO, 12);
    byte[] padded = Arrays.copyOf(plaintext, plaintext.length + 1);
    padded[plaintext.length] = 0x02;                        // last (and only) record, no extra padding
    byte[] ciphertext = aesGcm(Cipher.ENCRYPT_MODE, cek, nonce, padded);
    ByteBuffer header = ByteBuffer.allocate(SALT_LENGTH + 4 + 1 + POINT_LENGTH);
    header.put(salt).putInt(RECORD_SIZE).put((byte) POINT_LENGTH).put(asPoint);
    return concat(header.array(), ciphertext);
  }

  /** The raw ECDH shared secret (the X coordinate, 32 bytes). */
  static byte[] ecdh(ECPrivateKey privateKey, ECPublicKey publicKey) {
    try {
      KeyAgreement ka = KeyAgreement.getInstance("ECDH");
      ka.init(privateKey);
      ka.doPhase(publicKey, true);
      return ka.generateSecret();
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("ECDH failed", e);
    }
  }

  static byte[] aesGcm(int mode, byte[] key, byte[] nonce, byte[] input) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      return c.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM failed", e);
    }
  }

  /** HKDF (RFC 5869): Expand(Extract(salt, ikm), info, length). */
  static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
    return hkdfExpand(hkdfExtract(salt, ikm), info, length);
  }

  static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
    return hmac(salt, ikm);
  }

  /** HKDF-Expand for lengths up to 32 bytes (one block), which is all Web Push needs. */
  static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
    if (length > 32) {
      throw new IllegalArgumentException("HKDF-Expand here supports at most 32 bytes");
    }
    byte[] t = hmac(prk, concat(info, new byte[] {1}));
    return Arrays.copyOf(t, length);
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 failed", e);
    }
  }

  static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }
}
