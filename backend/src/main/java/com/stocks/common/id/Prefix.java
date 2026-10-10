package com.stocks.common.id;

/** Public-id prefixes (§6.1). A public id is {@code <prefix>_<ULID>}, at most 30 characters (column VARCHAR(32)). */
public enum Prefix {
  PLAYER("ply"),
  ACCOUNT("acc"),
  ORDER("ord"),
  EXECUTION("exe"),
  VIOLATION("vio"),
  FEED_EVENT("fde"),
  LEDGER("led"),
  CASH_TRANSFER("xfr"),
  TRANSFER_BATCH("bat"),
  INVITE("inv"),
  WATCHLIST("wlt"),
  CORPORATE_ACTION("cac"),
  NOTIFICATION("ntf"),
  ACCOUNT_EVENT("aev"),
  /** Server-generated {@code X-Request-Id} values (§6.6); never stored as a row id. */
  REQUEST("req");

  private final String code;

  Prefix(String code) {
    this.code = code;
  }

  /** The three-letter prefix, e.g. {@code ord}. */
  public String code() {
    return code;
  }

  public static Prefix fromCode(String code) {
    for (Prefix p : values()) {
      if (p.code.equals(code)) {
        return p;
      }
    }
    throw new IllegalArgumentException("Unknown public-id prefix: " + code);
  }
}
