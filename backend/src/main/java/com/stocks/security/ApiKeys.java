package com.stocks.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.random.RandomGenerator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The API-key format and its HMAC (§17.2). A token is {@code stk_<keyId>_<secret>}:
 * <ul>
 *   <li>{@code keyId}: 12 characters of lowercase base32 ({@code [a-z2-7]}), stored in {@code api_key.key_id};</li>
 *   <li>{@code secret}: 43 characters of base64url (32 random bytes), never stored;
 *       {@code api_key.secret_hmac} = lowercase hex HMAC-SHA256(pepper, secret).</li>
 * </ul>
 * Parsing is by fixed position (prefix, 12 characters, {@code _}, the rest), never by splitting on {@code _}, because
 * base64url secrets contain {@code _} and {@code -}. The HMAC key is the UTF-8 bytes of {@code API_KEY_PEPPER}.
 */
public final class ApiKeys {

  public static final String PREFIX = "stk_";
  public static final int KEY_ID_LENGTH = 12;
  public static final int SECRET_LENGTH = 43;
  /** {@code stk_} + 12 + {@code _} + 43. */
  public static final int TOKEN_LENGTH = PREFIX.length() + KEY_ID_LENGTH + 1 + SECRET_LENGTH;

  private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  /** A syntactically valid token, split into its public and secret parts. */
  public record Parsed(String keyId, String secret) {
    @Override
    public String toString() {
      return "ApiKeys.Parsed[keyId=" + keyId + ", secret=***]";
    }
  }

  private ApiKeys() {}

  /** Splits a token by fixed position; empty if it is not exactly {@code stk_<12 base32>_<43 base64url>}. */
  public static Optional<Parsed> parse(String token) {
    if (token == null || token.length() != TOKEN_LENGTH || !token.startsWith(PREFIX)) {
      return Optional.empty();
    }
    int idEnd = PREFIX.length() + KEY_ID_LENGTH;
    if (token.charAt(idEnd) != '_') {
      return Optional.empty();
    }
    String keyId = token.substring(PREFIX.length(), idEnd);
    String secret = token.substring(idEnd + 1);
    if (!isKeyId(keyId) || !isSecret(secret)) {
      return Optional.empty();
    }
    return Optional.of(new Parsed(keyId, secret));
  }

  public static boolean isKeyId(String s) {
    if (s == null || s.length() != KEY_ID_LENGTH) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!((c >= 'a' && c <= 'z') || (c >= '2' && c <= '7'))) {
        return false;
      }
    }
    return true;
  }

  static boolean isSecret(String s) {
    if (s == null || s.length() != SECRET_LENGTH) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
        return false;
      }
    }
    return true;
  }

  public static String format(String keyId, String secret) {
    return PREFIX + keyId + "_" + secret;
  }

  /** 12 random lowercase base32 characters (60 bits). */
  public static String newKeyId(RandomGenerator random) {
    char[] out = new char[KEY_ID_LENGTH];
    for (int i = 0; i < out.length; i++) {
      out[i] = BASE32[random.nextInt(BASE32.length)];
    }
    return new String(out);
  }

  /** 32 random bytes as 43 base64url characters. Pass a {@link java.security.SecureRandom}. */
  public static String newSecret(RandomGenerator random) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return BASE64URL.encodeToString(bytes);
  }

  /** Lowercase hex HMAC-SHA256(pepper, secret). */
  public static String hmacHex(String pepper, String secret) {
    if (pepper == null || pepper.isEmpty()) {
      throw new IllegalStateException("stocks.security.api-key-pepper (API_KEY_PEPPER) is not configured");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  /** Constant-time comparison of the secret's HMAC with the stored hex value. */
  public static boolean matches(String pepper, String secret, String storedHex) {
    if (storedHex == null) {
      return false;
    }
    byte[] actual = hmacHex(pepper, secret).getBytes(StandardCharsets.US_ASCII);
    byte[] expected = storedHex.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(actual, expected);
  }
}
