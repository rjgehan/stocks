package com.stocks.common.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * §7.1 (CONTRACT). Bar timeframes.
 * A wire-value enum (§6.3): JSON, query parameters, OpenAPI and MCP use the string in parentheses, case-insensitively.
 */
public enum Timeframe implements WireEnum {
  MIN1("1Min"), MIN5("5Min"), MIN15("15Min"), MIN30("30Min"), HOUR1("1Hour"),
  DAY1("1Day"), WEEK1("1Week"), MONTH1("1Month");

  private final String wire;

  Timeframe(String wire) {
    this.wire = wire;
  }

  @Override
  @JsonValue
  public String wireValue() {
    return wire;
  }

  /** Case-insensitive; also accepts the constant name. */
  @JsonCreator
  public static Timeframe fromWire(String value) {
    return WireEnum.parse(Timeframe.class, value);
  }

  @Override
  public String toString() {
    return wire;
  }
}
