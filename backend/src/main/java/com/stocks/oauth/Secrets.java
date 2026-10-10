package com.stocks.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Random values and hashes of the authorization server (§17.9.4): codes, refresh tokens, request ids and DCR
 * secrets are 32 random bytes in base64url (43 characters); only their SHA-256 (lowercase hex) is stored, and stored
 * hashes are compared in constant time.
 */
final class Secrets {

  /** PKCE verifier / challenge alphabet and length (RFC 7636 §4.1). */
  static final Pattern PKCE = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

  private Secrets() {}

  /** 32 random bytes as base64url without padding (43 characters). */
  static String random() {
    return random(32);
  }

  /** {@code n} random bytes as base64url without padding. */
  static String random(int n) {
    byte[] bytes = new byte[n];
    RANDOM.nextBytes(bytes);
    return B64URL.encodeToString(bytes);
  }

  static String sha256Hex(String value) {
    return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
  }

  /** RFC 7636 S256: {@code BASE64URL(SHA-256(ASCII(verifier)))}. */
  static String s256(String verifier) {
    return B64URL.encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
  }

  static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
