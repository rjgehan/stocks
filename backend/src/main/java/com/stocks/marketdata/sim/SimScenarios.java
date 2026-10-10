package com.stocks.marketdata.sim;

import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.time.Et;
import com.stocks.contract.marketdata.model.CorporateActionData;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SIM scenarios (§15.7 item 7), loaded from {@code classpath:marketdata/sim/scenarios/<name>.yml} for the names in
 * {@code stocks.marketdata.sim.scenarios}. A scenario names its symbols (by default the SIM test symbols, so real
 * tickers are untouched), an ET {@code time} and {@code duration}, and either a {@code date} (once) or nothing
 * (every trading day). Types:
 * <ul>
 *   <li>{@code halt-resume}: a trading halt ({@code StatusEvent(halted=true)}); quotes and prints freeze.</li>
 *   <li>{@code feed-blackout}: no updates and no confirmations, so the symbol goes {@code STALE}.</li>
 *   <li>{@code one-sided-quote}: the bid disappears.</li>
 *   <li>{@code flash-crash}: a V-shaped dip of {@code depth} (a fraction) over {@code duration}.</li>
 *   <li>{@code gap}: an extra overnight gap of {@code gapPct} at the open of each listed {@code dates} entry.</li>
 *   <li>{@code split}: a forward or reverse split on {@code exDate} ({@code oldRate} → {@code newRate}); raw prices
 *       before the ex-date are scaled accordingly.</li>
 *   <li>{@code cash-dividend}: a dividend of {@code cash} per share with {@code exDate}/{@code payableDate}.</li>
 * </ul>
 * The YAML subset is flat {@code key: value} lines with inline {@code [a, b]} lists.
 */
public final class SimScenarios {

  public static final String RESOURCE_DIR = "marketdata/sim/scenarios/";

  /** A loaded scenario. Unused fields are null. */
  public record Scenario(String name, String type, Set<String> symbols, LocalDate date, LocalTime time,
                         Duration duration, double depth, List<LocalDate> dates, double gapPct, LocalDate exDate,
                         LocalDate payableDate, BigDecimal oldRate, BigDecimal newRate, BigDecimal cash) {

    boolean appliesTo(String symbol) {
      return symbols.contains(symbol);
    }

    /** The active window containing {@code t}, if any (once on {@code date}, else daily at {@code time} ET). */
    Optional<Instant[]> windowAt(Instant t) {
      if (time == null || duration == null) {
        return Optional.empty();
      }
      LocalDate d = date != null ? date : Et.date(t);
      Instant start = Et.at(d, time);
      Instant end = start.plus(duration);
      return !t.isBefore(start) && t.isBefore(end) ? Optional.of(new Instant[] {start, end}) : Optional.empty();
    }
  }

  private static final SimScenarios NONE = new SimScenarios(List.of());

  private final List<Scenario> scenarios;

  public SimScenarios(List<Scenario> scenarios) {
    this.scenarios = List.copyOf(scenarios);
  }

  public static SimScenarios none() {
    return NONE;
  }

  public static SimScenarios load(List<String> names) {
    List<Scenario> out = new ArrayList<>();
    for (String n : names) {
      String name = n.trim();
      if (!name.isEmpty()) {
        out.add(loadOne(name));
      }
    }
    return new SimScenarios(out);
  }

  public List<Scenario> all() {
    return scenarios;
  }

  static Scenario loadOne(String name) {
    String resource = RESOURCE_DIR + name + ".yml";
    try (InputStream in = SimScenarios.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Unknown SIM scenario '" + name + "' (no classpath:" + resource + ")");
      }
      return parse(name, new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Scenario parse(String name, BufferedReader reader) throws IOException {
    Map<String, String> kv = new LinkedHashMap<>();
    String line;
    while ((line = reader.readLine()) != null) {
      int hash = line.indexOf('#');
      String l = (hash >= 0 ? line.substring(0, hash) : line).trim();
      int colon = l.indexOf(':');
      if (l.isEmpty() || colon <= 0) {
        continue;
      }
      String value = l.substring(colon + 1).trim();
      if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
          || value.startsWith("'") && value.endsWith("'"))) {
        value = value.substring(1, value.length() - 1);
      }
      kv.put(l.substring(0, colon).trim(), value);
    }
    String type = req(kv, "type", name).toLowerCase(Locale.ROOT);
    Set<String> symbols = Set.copyOf(list(kv.get("symbols")).stream().map(s -> s.toUpperCase(Locale.ROOT)).toList());
    if (symbols.isEmpty()) {
      throw new IllegalStateException("SIM scenario " + name + ": symbols is required");
    }
    return new Scenario(name, type, symbols, date(kv.get("date")), time(kv.get("time")),
        kv.containsKey("duration") ? Duration.parse(kv.get("duration")) : null,
        kv.containsKey("depth") ? Double.parseDouble(kv.get("depth")) : 0,
        list(kv.get("dates")).stream().map(LocalDate::parse).toList(),
        kv.containsKey("gapPct") ? Double.parseDouble(kv.get("gapPct")) : 0,
        date(kv.get("exDate")), date(kv.get("payableDate")), dec(kv.get("oldRate")), dec(kv.get("newRate")),
        dec(kv.get("cash")));
  }

  private static String req(Map<String, String> kv, String key, String name) {
    String v = kv.get(key);
    if (v == null || v.isBlank()) {
      throw new IllegalStateException("SIM scenario " + name + ": " + key + " is required");
    }
    return v;
  }

  private static List<String> list(String v) {
    if (v == null || v.isBlank() || v.equals("null")) {
      return List.of();
    }
    String s = v.trim();
    if (s.startsWith("[") && s.endsWith("]")) {
      s = s.substring(1, s.length() - 1);
    }
    return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
  }

  private static LocalDate date(String v) {
    return v == null || v.isBlank() || v.equals("null") ? null : LocalDate.parse(v);
  }

  private static LocalTime time(String v) {
    return v == null || v.isBlank() || v.equals("null") ? null : LocalTime.parse(v);
  }

  private static BigDecimal dec(String v) {
    return v == null || v.isBlank() || v.equals("null") ? null : new BigDecimal(v);
  }

  // ------------------------------------------------------------------------------------------- effects

  private List<Scenario> of(String type, String symbol) {
    if (scenarios.isEmpty()) {
      return List.of();
    }
    return scenarios.stream().filter(s -> s.type().equals(type) && s.appliesTo(symbol)).toList();
  }

  /** Start of the halt or blackout covering {@code t}: quotes and prints freeze there. */
  Optional<Instant> frozenSince(String symbol, Instant t) {
    Instant best = null;
    for (String type : List.of("halt-resume", "feed-blackout")) {
      for (Scenario s : of(type, symbol)) {
        Optional<Instant[]> w = s.windowAt(t);
        if (w.isPresent() && (best == null || w.get()[0].isBefore(best))) {
          best = w.get()[0];
        }
      }
    }
    return Optional.ofNullable(best);
  }

  boolean halted(String symbol, Instant t) {
    return of("halt-resume", symbol).stream().anyMatch(s -> s.windowAt(t).isPresent());
  }

  /** Start of an active blackout (the last confirmation), if any. */
  Optional<Instant> blackoutSince(String symbol, Instant t) {
    return of("feed-blackout", symbol).stream().map(s -> s.windowAt(t)).flatMap(Optional::stream)
        .map(w -> w[0]).findFirst();
  }

  boolean oneSided(String symbol, Instant t) {
    return of("one-sided-quote", symbol).stream().anyMatch(s -> s.windowAt(t).isPresent());
  }

  /** Additive log-price dip of an active flash crash (≤ 0). */
  double logDip(String symbol, Instant t) {
    double dip = 0;
    for (Scenario s : of("flash-crash", symbol)) {
      Optional<Instant[]> w = s.windowAt(t);
      if (w.isPresent()) {
        double len = w.get()[1].toEpochMilli() - w.get()[0].toEpochMilli();
        double x = (t.toEpochMilli() - w.get()[0].toEpochMilli()) / len;
        double tri = x < 0.5 ? x * 2 : (1 - x) * 2;
        dip += StrictMath.log(1 - s.depth()) * tri;
      }
    }
    return dip;
  }

  /** Extra log return at the open of {@code date} (a gap scenario). */
  double gapLog(String symbol, LocalDate date) {
    double g = 0;
    for (Scenario s : of("gap", symbol)) {
      if (s.dates().contains(date)) {
        g += StrictMath.log(1 + s.gapPct());
      }
    }
    return g;
  }

  /** Raw-price factor for prices dated {@code date}: Π newRate/oldRate... inverted, over splits with exDate > date. */
  BigDecimal rawFactorNumerator(String symbol, LocalDate date) {
    BigDecimal f = BigDecimal.ONE;
    for (Scenario s : of("split", symbol)) {
      if (s.exDate() != null && date.isBefore(s.exDate())) {
        f = f.multiply(s.oldRate());
      }
    }
    return f;
  }

  BigDecimal rawFactorDenominator(String symbol, LocalDate date) {
    BigDecimal f = BigDecimal.ONE;
    for (Scenario s : of("split", symbol)) {
      if (s.exDate() != null && date.isBefore(s.exDate())) {
        f = f.multiply(s.newRate());
      }
    }
    return f;
  }

  boolean hasSplits(String symbol) {
    return !of("split", symbol).isEmpty();
  }

  /** Provider corporate actions for {@code symbols} with an ex-date in {@code [from, to]}. */
  List<CorporateActionData> corporateActions(Set<String> symbols, LocalDate from, LocalDate to) {
    List<CorporateActionData> out = new ArrayList<>();
    for (Scenario s : scenarios) {
      if (s.exDate() == null || s.exDate().isBefore(from) || s.exDate().isAfter(to)) {
        continue;
      }
      for (String sym : s.symbols()) {
        if (!symbols.isEmpty() && !symbols.contains(sym)) {
          continue;
        }
        String ref = "sim:" + s.name() + ":" + sym + ":" + s.exDate();
        if (s.type().equals("split")) {
          CorporateActionType t = s.newRate().compareTo(s.oldRate()) > 0 ? CorporateActionType.FORWARD_SPLIT
              : CorporateActionType.REVERSE_SPLIT;
          out.add(new CorporateActionData(ref, sym, t, s.exDate(), s.exDate(), s.exDate(), s.oldRate(), s.newRate(),
              null, null));
        } else if (s.type().equals("cash-dividend")) {
          out.add(new CorporateActionData(ref, sym, CorporateActionType.CASH_DIVIDEND, s.exDate(), s.exDate(),
              s.payableDate() != null ? s.payableDate() : s.exDate().plusDays(14), null, null, s.cash(), null));
        }
      }
    }
    return out;
  }
}
