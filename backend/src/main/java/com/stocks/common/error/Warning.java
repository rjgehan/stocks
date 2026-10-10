package com.stocks.common.error;

/**
 * A non-blocking warning in a successful response: {@code warnings: [{code, message}]} (§6.5, §18.3 {@code Warning}).
 * {@code code} is normally a {@link WarningCode} name.
 */
public record Warning(String code, String message) {

  public static Warning of(WarningCode code, String message) {
    return new Warning(code.name(), message);
  }
}
