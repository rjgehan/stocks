package com.stocks.common.web;

/**
 * String hygiene for client-controlled text (§6.6, §17.8). {@link #clip} is what {@code RequestMeta} applies to
 * {@code User-Agent} (300), MCP client name (120) and MCP tool (64), so writers never see an unbounded header.
 */
public final class Text {

  private Text() {}

  /**
   * Strips control characters, then truncates to at most {@code maxChars} UTF-16 units without splitting a
   * surrogate pair. Null-safe ({@code null} stays {@code null}).
   */
  public static String clip(String s, int maxChars) {
    if (s == null) {
      return null;
    }
    if (maxChars < 0) {
      throw new IllegalArgumentException("maxChars < 0");
    }
    String clean = stripControl(s);
    if (clean.length() <= maxChars) {
      return clean;
    }
    int end = maxChars;
    if (end > 0 && Character.isHighSurrogate(clean.charAt(end - 1))) {
      end--;
    }
    return clean.substring(0, end);
  }

  /**
   * Removes ISO control characters (C0, DEL, C1: tabs and newlines included) and Unicode format characters such as
   * zero-width spaces and bidi overrides. Null-safe.
   */
  public static String stripControl(String s) {
    if (s == null) {
      return null;
    }
    StringBuilder out = null;
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      int n = Character.charCount(cp);
      boolean drop = Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT;
      if (drop && out == null) {
        out = new StringBuilder(s.length());
        out.append(s, 0, i);
      } else if (!drop && out != null) {
        out.appendCodePoint(cp);
      }
      i += n;
    }
    return out == null ? s : out.toString();
  }

  /** {@code null}, empty or whitespace only. */
  public static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** Trimmed value, or {@code null} if blank. */
  public static String trimToNull(String s) {
    return isBlank(s) ? null : s.trim();
  }
}
