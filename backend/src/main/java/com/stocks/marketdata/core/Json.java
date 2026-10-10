package com.stocks.marketdata.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON reading for provider payloads. Numbers are read as {@link BigDecimal} from their exact text
 * ({@code USE_BIG_DECIMAL_FOR_FLOATS}), so a price never passes through {@code double} (D4). Timestamps are RFC-3339
 * with up to nanoseconds; instants are truncated to microseconds (§6.4).
 */
public final class Json {

  public static final JsonMapper MAPPER = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .build();

  private Json() {}

  public static JsonNode parse(String text) {
    return MAPPER.readTree(text);
  }

  public static String write(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  public static boolean present(JsonNode n, String field) {
    JsonNode v = n == null ? null : n.get(field);
    return v != null && !v.isNull() && !v.isMissingNode();
  }

  public static String str(JsonNode n, String field) {
    return present(n, field) ? n.get(field).asString() : null;
  }

  public static BigDecimal dec(JsonNode n, String field) {
    if (!present(n, field)) {
      return null;
    }
    JsonNode v = n.get(field);
    if (v.isNumber()) {
      return v.decimalValue();
    }
    String s = v.asString();
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public static long lng(JsonNode n, String field, long dflt) {
    if (!present(n, field)) {
      return dflt;
    }
    JsonNode v = n.get(field);
    if (v.isNumber()) {
      return v.decimalValue().longValue();
    }
    try {
      return Long.parseLong(v.asString().trim());
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  public static boolean bool(JsonNode n, String field, boolean dflt) {
    return present(n, field) ? n.get(field).asBoolean(dflt) : dflt;
  }

  /** RFC-3339 with any fraction (Alpaca sends nanoseconds) → instant truncated to micros. */
  public static Instant instant(JsonNode n, String field) {
    String s = str(n, field);
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(s).toInstant().truncatedTo(ChronoUnit.MICROS);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  public static LocalDate date(JsonNode n, String field) {
    String s = str(n, field);
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  public static List<String> strings(JsonNode n, String field) {
    List<String> out = new ArrayList<>();
    if (present(n, field) && n.get(field).isArray()) {
      for (JsonNode v : n.get(field).values()) {
        out.add(v.asString());
      }
    }
    return out;
  }
}
