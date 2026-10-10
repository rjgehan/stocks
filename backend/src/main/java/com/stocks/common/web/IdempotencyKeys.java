package com.stocks.common.web;

import com.stocks.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The {@code Idempotency-Key} header of non-order writes (§6.6, §17.6): required on admin cash transfers, admin
 * resets and self-reset; 1–80 characters of {@code [A-Za-z0-9._:-]}. A replayed response carries
 * {@code Idempotent-Replay: true}. {@link #requestHash} is the {@code IdempotencyRef.requestHash} every caller sends
 * into the engine command: lowercase hex SHA-256 over the request's canonical parts, joined by a newline.
 */
public final class IdempotencyKeys {

  public static final String HEADER = "Idempotency-Key";
  public static final String REPLAY_HEADER = "Idempotent-Replay";
  public static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,80}$");

  private IdempotencyKeys() {}

  /**
   * The validated header value.
   *
   * @throws ApiException 400 {@code VALIDATION_FAILED} (field {@code Idempotency-Key}) when missing or malformed
   */
  public static String require(String headerValue) {
    if (headerValue == null || headerValue.isBlank()) {
      throw ApiException.validation(HEADER, "Required: a unique key per request (1-80 of A-Z a-z 0-9 . _ : -); "
          + "reuse it when retrying");
    }
    String key = headerValue.trim();
    if (!PATTERN.matcher(key).matches()) {
      throw ApiException.validation(HEADER, "1-80 characters of A-Z a-z 0-9 . _ : -");
    }
    return key;
  }

  /** Lowercase hex SHA-256 of {@code parts} joined by {@code '\n'} (null parts become the empty string). */
  public static String requestHash(String... parts) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        sb.append('\n');
      }
      sb.append(parts[i] == null ? "" : parts[i]);
    }
    return sha256Hex(sb.toString());
  }

  /** Lowercase hex SHA-256 of the UTF-8 bytes of {@code s}. */
  public static String sha256Hex(String s) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
