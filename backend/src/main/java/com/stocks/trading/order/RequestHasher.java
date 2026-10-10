package com.stocks.trading.order;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The order idempotency hash (§11.7): hex SHA-256 of the canonical JSON of the normalized business fields
 * {@code {symbol, side, type, qty, notional, limitPrice, stopPrice, trailAmount, trailPercent, timeInForce,
 * extendedHours}}, keys sorted, decimals stripped of trailing zeros, the symbol upper-cased. {@code rationale},
 * {@code shareNote} and {@code acknowledge} are excluded, so a retry that only adds an acknowledgement still replays.
 */
public final class RequestHasher {

  private RequestHasher() {}

  public static String hash(NormalizedOrder o) {
    Map<String, Object> fields = new TreeMap<>();
    fields.put("extendedHours", o.extendedHours());
    fields.put("limitPrice", dec(o.limitPrice()));
    fields.put("notional", dec(o.notional()));
    fields.put("qty", dec(o.qty()));
    fields.put("side", o.side() == null ? null : o.side().name());
    fields.put("stopPrice", dec(o.stopPrice()));
    fields.put("symbol", o.symbol());
    fields.put("timeInForce", o.timeInForce() == null ? null : o.timeInForce().name());
    fields.put("trailAmount", dec(o.trailAmount()));
    fields.put("trailPercent", dec(o.trailPercent()));
    fields.put("type", o.type() == null ? null : o.type().name());
    return sha256Hex(canonicalJson(fields));
  }

  /** {@code {"a":…,"b":…}} with the map's key order; strings JSON-escaped, decimals as strings. */
  static String canonicalJson(Map<String, Object> fields) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> e : fields.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append(quote(e.getKey())).append(':');
      Object v = e.getValue();
      if (v == null) {
        sb.append("null");
      } else if (v instanceof Boolean b) {
        sb.append(b);
      } else {
        sb.append(quote(v.toString()));
      }
    }
    return sb.append('}').toString();
  }

  private static String dec(BigDecimal v) {
    if (v == null) {
      return null;
    }
    BigDecimal s = v.stripTrailingZeros();
    return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
  }

  private static String quote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }

  public static String sha256Hex(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
