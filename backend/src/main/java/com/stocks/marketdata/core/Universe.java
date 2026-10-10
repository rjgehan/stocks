package com.stocks.marketdata.core;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.contract.marketdata.model.Asset;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The bundled symbol universe ({@code classpath:marketdata/universe.csv}, ~690 symbols: S&P 500, Nasdaq-100 extras,
 * popular stocks and ETFs). It seeds the {@code asset} table when nothing better is known, drives the movers fallback
 * and the universe poll (§15.5), and gives the SIM its base prices ({@code ref_price}, §15.7).
 */
public final class Universe {

  public static final String RESOURCE = "marketdata/universe.csv";

  /** One CSV row. */
  public record Entry(String symbol, String name, String exchange, AssetType type, LiquidityTier tier,
                      BigDecimal refPrice, boolean fractionable) {

    public Asset asset() {
      return new Asset(symbol, name, exchange, type, true, fractionable, false, false, false, tier, true);
    }
  }

  private final Map<String, Entry> entries;

  private Universe(Map<String, Entry> entries) {
    this.entries = entries;
  }

  public static Universe load() {
    try (InputStream in = Universe.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing classpath resource " + RESOURCE);
      }
      return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Universe parse(BufferedReader reader) throws IOException {
    Map<String, Entry> out = new LinkedHashMap<>();
    String line;
    int lineNo = 0;
    while ((line = reader.readLine()) != null) {
      lineNo++;
      String l = line.trim();
      if (l.isEmpty() || l.startsWith("#") || l.startsWith("symbol,")) {
        continue;
      }
      String[] f = l.split(",", -1);
      if (f.length != 7) {
        throw new IllegalStateException(RESOURCE + ":" + lineNo + ": expected 7 fields, got " + f.length);
      }
      String sym = f[0].trim().toUpperCase(Locale.ROOT);
      out.put(sym, new Entry(sym, f[1].trim(), f[2].trim(), AssetType.valueOf(f[3].trim()),
          LiquidityTier.valueOf(f[4].trim()), new BigDecimal(f[5].trim()), Boolean.parseBoolean(f[6].trim())));
    }
    return new Universe(Map.copyOf(out));
  }

  public Optional<Entry> get(String symbol) {
    return Optional.ofNullable(entries.get(symbol));
  }

  public boolean contains(String symbol) {
    return entries.containsKey(symbol);
  }

  public Collection<Entry> entries() {
    return entries.values();
  }

  public List<String> symbols() {
    return entries.keySet().stream().sorted().toList();
  }

  public int size() {
    return entries.size();
  }
}
