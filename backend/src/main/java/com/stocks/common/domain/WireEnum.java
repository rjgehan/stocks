package com.stocks.common.domain;

import java.util.Locale;

/**
 * An enum whose API value is not its constant name (§6.3): {@link HistoryRange}, {@link ChartRange} and
 * {@link Timeframe} travel as {@code "1D"}, {@code "1Min"} and so on. JSON uses the {@code @JsonValue}/{@code @JsonCreator}
 * on each enum; query parameters bind through {@code config.WebConfig}; OpenAPI publishes the wire values.
 */
public interface WireEnum {

  /** The value used in JSON, query parameters, OpenAPI and MCP. */
  String wireValue();

  /**
   * Case-insensitive lookup by wire value, falling back to the constant name.
   *
   * @throws IllegalArgumentException if nothing matches (the web layer maps it to {@code VALIDATION_FAILED})
   */
  static <E extends Enum<E> & WireEnum> E parse(Class<E> type, String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    for (E e : type.getEnumConstants()) {
      if (e.wireValue().equalsIgnoreCase(v)) {
        return e;
      }
    }
    for (E e : type.getEnumConstants()) {
      if (e.name().equalsIgnoreCase(v)) {
        return e;
      }
    }
    throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " '" + value + "'; expected one of "
        + java.util.Arrays.stream(type.getEnumConstants()).map(WireEnum::wireValue).toList());
  }

  /** Lower-cased wire value, handy for keys (cache names, STOMP topics). */
  default String wireKey() {
    return wireValue().toLowerCase(Locale.ROOT);
  }
}
