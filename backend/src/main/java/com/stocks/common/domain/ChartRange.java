package com.stocks.common.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * §7.1 (CONTRACT). Chart ranges.
 * A wire-value enum (§6.3): JSON, query parameters, OpenAPI and MCP use the string in parentheses, case-insensitively.
 */
public enum ChartRange implements WireEnum {
  D1("1D"), D5("5D"), M1("1M"), M3("3M"), M6("6M"), YTD("YTD"), Y1("1Y"), Y5("5Y"), ALL("ALL");

  private final String wire;

  ChartRange(String wire) {
    this.wire = wire;
  }

  @Override
  @JsonValue
  public String wireValue() {
    return wire;
  }

  /** Case-insensitive; also accepts the constant name. */
  @JsonCreator
  public static ChartRange fromWire(String value) {
    return WireEnum.parse(ChartRange.class, value);
  }

  @Override
  public String toString() {
    return wire;
  }
}
