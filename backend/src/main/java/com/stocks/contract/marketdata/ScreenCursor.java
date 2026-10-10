package com.stocks.contract.marketdata;

import com.stocks.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** The opaque screener cursor (§15.10): base64url of {@code "s:<offset>"}; shared by REST and MCP. */
public final class ScreenCursor {

  /** The deepest page a cursor may point at (the universe is ~690 symbols). */
  public static final int MAX_OFFSET = 10_000;

  private ScreenCursor() {}

  public static String encode(int offset) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(("s:" + offset).getBytes(StandardCharsets.UTF_8));
  }

  /** 0 for a null or blank cursor; {@code VALIDATION_FAILED} on {@code field} for anything not made by encode. */
  public static int decode(String cursor, String field) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      String s = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
      if (s.startsWith("s:")) {
        int offset = Integer.parseInt(s.substring(2));
        if (offset >= 0 && offset <= MAX_OFFSET) {
          return offset;
        }
      }
    } catch (IllegalArgumentException e) {
      // falls through
    }
    throw ApiException.validation(field, "not a cursor from a previous screen");
  }
}
