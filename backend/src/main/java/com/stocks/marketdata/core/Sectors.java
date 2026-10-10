package com.stocks.marketdata.core;

import com.stocks.contract.marketdata.Sector;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * GICS sectors for the screener (§15.10): the static map {@code classpath:marketdata/sectors.csv} (every non-ETF
 * universe symbol), and {@link #fromIndustry} for a symbol outside it that has a Finnhub industry label
 * ({@code finnhubIndustry}, e.g. "Semiconductors"). ETFs have no sector.
 */
public final class Sectors {

  public static final String RESOURCE = "marketdata/sectors.csv";

  /** Finnhub industry keywords → sector, checked in order (first match wins); lower-case substrings. */
  private static final List<Map.Entry<String, Sector>> INDUSTRY_KEYWORDS = List.of(
      Map.entry("real estate", Sector.REAL_ESTATE), Map.entry("reit", Sector.REAL_ESTATE),
      Map.entry("semiconductor", Sector.INFORMATION_TECHNOLOGY), Map.entry("software", Sector.INFORMATION_TECHNOLOGY),
      Map.entry("technology", Sector.INFORMATION_TECHNOLOGY), Map.entry("electronic", Sector.INFORMATION_TECHNOLOGY),
      Map.entry("communications equipment", Sector.INFORMATION_TECHNOLOGY),
      Map.entry("telecommunication", Sector.COMMUNICATION_SERVICES), Map.entry("media", Sector.COMMUNICATION_SERVICES),
      Map.entry("entertainment", Sector.COMMUNICATION_SERVICES),
      Map.entry("pharmaceutical", Sector.HEALTH_CARE), Map.entry("biotech", Sector.HEALTH_CARE),
      Map.entry("health", Sector.HEALTH_CARE), Map.entry("life sciences", Sector.HEALTH_CARE),
      Map.entry("medical", Sector.HEALTH_CARE),
      Map.entry("bank", Sector.FINANCIALS), Map.entry("insurance", Sector.FINANCIALS),
      Map.entry("financial", Sector.FINANCIALS), Map.entry("capital markets", Sector.FINANCIALS),
      Map.entry("utilit", Sector.UTILITIES),
      Map.entry("oil", Sector.ENERGY), Map.entry("gas", Sector.ENERGY), Map.entry("energy", Sector.ENERGY),
      Map.entry("chemical", Sector.MATERIALS), Map.entry("metals", Sector.MATERIALS), Map.entry("mining", Sector.MATERIALS),
      Map.entry("packaging", Sector.MATERIALS), Map.entry("construction materials", Sector.MATERIALS),
      Map.entry("beverage", Sector.CONSUMER_STAPLES), Map.entry("food", Sector.CONSUMER_STAPLES),
      Map.entry("tobacco", Sector.CONSUMER_STAPLES), Map.entry("household", Sector.CONSUMER_STAPLES),
      Map.entry("personal products", Sector.CONSUMER_STAPLES),
      Map.entry("retail", Sector.CONSUMER_DISCRETIONARY), Map.entry("automobile", Sector.CONSUMER_DISCRETIONARY),
      Map.entry("hotels", Sector.CONSUMER_DISCRETIONARY), Map.entry("leisure", Sector.CONSUMER_DISCRETIONARY),
      Map.entry("textiles", Sector.CONSUMER_DISCRETIONARY), Map.entry("consumer", Sector.CONSUMER_DISCRETIONARY),
      Map.entry("aerospace", Sector.INDUSTRIALS), Map.entry("airlines", Sector.INDUSTRIALS),
      Map.entry("machinery", Sector.INDUSTRIALS), Map.entry("logistics", Sector.INDUSTRIALS),
      Map.entry("transportation", Sector.INDUSTRIALS), Map.entry("road & rail", Sector.INDUSTRIALS),
      Map.entry("construction", Sector.INDUSTRIALS), Map.entry("industrial", Sector.INDUSTRIALS),
      Map.entry("professional services", Sector.INDUSTRIALS), Map.entry("commercial services", Sector.INDUSTRIALS),
      Map.entry("building", Sector.INDUSTRIALS), Map.entry("electrical equipment", Sector.INDUSTRIALS));

  private final Map<String, Sector> bySymbol;

  private Sectors(Map<String, Sector> bySymbol) {
    this.bySymbol = Map.copyOf(bySymbol);
  }

  public static Sectors load() {
    try (InputStream in = Sectors.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing classpath resource " + RESOURCE);
      }
      return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Sectors parse(BufferedReader reader) throws IOException {
    Map<String, Sector> out = new LinkedHashMap<>();
    String line;
    int lineNo = 0;
    while ((line = reader.readLine()) != null) {
      lineNo++;
      String l = line.trim();
      if (l.isEmpty() || l.startsWith("#") || l.startsWith("symbol,")) {
        continue;
      }
      String[] f = l.split(",", -1);
      if (f.length != 2) {
        throw new IllegalStateException(RESOURCE + ":" + lineNo + ": expected 2 fields, got " + f.length);
      }
      int n = lineNo;
      Sector sector = Sector.parse(f[1]).orElseThrow(
          () -> new IllegalStateException(RESOURCE + ":" + n + ": unknown sector '" + f[1].trim() + "'"));
      out.put(f[0].trim().toUpperCase(Locale.ROOT), sector);
    }
    return new Sectors(out);
  }

  /** The static sector of a symbol, if the bundled map has it. */
  public Optional<Sector> of(String symbol) {
    return Optional.ofNullable(bySymbol.get(symbol));
  }

  /** The sector of a symbol: the bundled map first, else the Finnhub industry label, else empty. */
  public Optional<Sector> of(String symbol, String industry) {
    Optional<Sector> s = of(symbol);
    return s.isPresent() ? s : fromIndustry(industry);
  }

  public int size() {
    return bySymbol.size();
  }

  /** A sector from a Finnhub industry label (first keyword match), or empty. */
  public static Optional<Sector> fromIndustry(String industry) {
    if (industry == null || industry.isBlank()) {
      return Optional.empty();
    }
    Optional<Sector> exact = Sector.parse(industry);
    if (exact.isPresent()) {
      return exact;
    }
    String i = industry.toLowerCase(Locale.ROOT);
    for (Map.Entry<String, Sector> e : INDUSTRY_KEYWORDS) {
      if (i.contains(e.getKey())) {
        return Optional.of(e.getValue());
      }
    }
    return Optional.empty();
  }
}
