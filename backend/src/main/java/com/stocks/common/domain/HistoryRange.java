package com.stocks.common.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * §7.1 (CONTRACT). Portfolio history ranges.
 * A wire-value enum (§6.3): JSON, query parameters, OpenAPI and MCP use the string in parentheses, case-insensitively.
 */
public enum HistoryRange implements WireEnum {
  D1("1D"), W1("1W"), M1("1M"), M3("3M"), YTD("YTD"), Y1("1Y"), ALL("ALL");

  private final String wire;

  HistoryRange(String wire) {
    this.wire = wire;
  }

  @Override
  @JsonValue
  public String wireValue() {
    return wire;
  }

  /** Case-insensitive; also accepts the constant name. */
  @JsonCreator
  public static HistoryRange fromWire(String value) {
    return WireEnum.parse(HistoryRange.class, value);
  }

  @Override
  public String toString() {
    return wire;
  }
}
