package com.stocks.admin;

import com.stocks.common.error.ApiException;
import com.stocks.common.web.Text;

/** Free-text fields of admin requests (reasons, memos): control characters stripped, trimmed, length-checked. */
public final class AdminText {

  private AdminText() {}

  /**
   * The cleaned value of {@code min..max} characters.
   *
   * @throws ApiException 400 {@code VALIDATION_FAILED} on {@code field}
   */
  public static String require(String raw, String field, int min, int max) {
    String r = raw == null ? "" : Text.stripControl(raw).trim();
    if (r.length() < min || r.length() > max) {
      throw ApiException.validation(field, min + "-" + max + " characters");
    }
    return r;
  }
}
