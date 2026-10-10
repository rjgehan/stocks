package com.stocks.common.web;

import com.stocks.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * An opaque keyset-pagination cursor (§6.6). The token encodes the sort key of the last row returned, normally
 * {@code (timestamp, id)} for newest-first lists; clients treat it as an opaque string. A malformed token is a
 * {@code VALIDATION_FAILED} on field {@code cursor}.
 *
 * <pre>{@code
 * Cursor c = Cursor.parse(cursorParam);           // null when absent
 * ... WHERE (received_at, id) < (?, ?) ...        // bind Jdbc.ts(c.at()), c.id()
 * Page.fromLookahead(rows, limit, r -> Cursor.of(r.receivedAt(), r.id()));
 * }</pre>
 */
public record Cursor(Instant at, long id) {

  private static final String VERSION = "c1";

  public Cursor {
    Objects.requireNonNull(at, "at");
  }

  public static Cursor of(Instant at, long id) {
    return new Cursor(at, id);
  }

  /** The opaque URL-safe token. */
  public String token() {
    String raw = VERSION + ":" + at.getEpochSecond() + ":" + at.getNano() + ":" + id;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
  }

  /** Parses a token from a request; {@code null} or blank → {@code null} (first page). */
  public static Cursor parse(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(token.trim()), StandardCharsets.US_ASCII);
      String[] parts = raw.split(":", -1);
      if (parts.length != 4 || !VERSION.equals(parts[0])) {
        throw new IllegalArgumentException("bad cursor");
      }
      Instant at = Instant.ofEpochSecond(Long.parseLong(parts[1]), Long.parseLong(parts[2]));
      return new Cursor(at, Long.parseLong(parts[3]));
    } catch (RuntimeException e) {
      throw ApiException.validation("cursor", "cursor is not a valid page cursor");
    }
  }

  @Override
  public String toString() {
    return token();
  }
}
