package com.stocks.marketdata.web;

import com.stocks.common.domain.Crypto;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.model.Asset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Symbol parameters of the market endpoints: upper case, 1–12 characters of {@code A–Z 0–9 . - /}, or an OCC option
 * contract (§30).
 */
final class Symbols {

  static final Pattern SYMBOL = Pattern.compile("^[A-Z0-9][A-Z0-9.\\-/]{0,11}$|^[A-Z]{1,6}[0-9]{6}[CP][0-9]{8}$");

  private Symbols() {}

  static String normalize(String raw, String field) {
    String s = raw == null ? "" : Crypto.fromPath(raw.trim().toUpperCase(Locale.ROOT));   // BTC-USD → BTC/USD
    if (!SYMBOL.matcher(s).matches()) {
      throw ApiException.validation(field, "not a valid symbol: '" + clip(raw) + "'");
    }
    return s;
  }

  /** A comma-separated list, deduplicated in order. */
  static List<String> list(String csv, String field, int max) {
    Set<String> out = new LinkedHashSet<>();
    if (csv != null) {
      for (String part : csv.split(",")) {
        if (!part.isBlank()) {
          out.add(normalize(part, field));
        }
      }
    }
    if (out.size() > max) {
      throw ApiException.validation(field, "at most " + max + " symbols");
    }
    return new ArrayList<>(out);
  }

  /** The asset, or {@code SYMBOL_NOT_FOUND}. */
  static Asset require(MarketDataService marketData, String symbol) {
    return marketData.asset(symbol).orElseThrow(() -> ApiException.of(ErrorCode.SYMBOL_NOT_FOUND,
        "Unknown symbol " + symbol + ".").withHint("Search with GET /api/v1/assets/search?q=" + symbol));
  }

  private static String clip(String s) {
    if (s == null) {
      return "";
    }
    String t = s.replaceAll("\\p{Cntrl}", "");
    return t.length() > 20 ? t.substring(0, 20) + "…" : t;
  }
}
