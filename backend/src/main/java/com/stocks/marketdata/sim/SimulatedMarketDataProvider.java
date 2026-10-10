package com.stocks.marketdata.sim;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.MoverList;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.CorporateActionData;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.contract.marketdata.model.Mover;
import com.stocks.contract.marketdata.model.Movers;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Snapshot;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.contract.marketdata.model.RecommendationTrend;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.marketdata.spi.CompanyProfile;
import com.stocks.marketdata.core.BarMath;
import com.stocks.marketdata.core.Universe;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.marketdata.spi.Capabilities;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.PointInTimeMarket;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The SIM provider (§15.7): the deterministic {@link SimModel} behind the provider SPI and {@link PointInTimeMarket}.
 * Its universe is {@code universe.csv} plus the fake test symbols {@code SIMA}–{@code SIMZ} (fractionable, MEGA).
 * Everything is supported, quotes are consolidated, there is no symbol limit, and every payload carries quality
 * {@code SIMULATED} and source {@code simulated}.
 */
public class SimulatedMarketDataProvider implements MarketDataProvider, PointInTimeMarket {

  public static final String ID = "simulated";

  static final Capabilities CAPABILITIES = new Capabilities(true, true, true, Integer.MAX_VALUE, Integer.MAX_VALUE,
      Duration.ZERO, null, null, true, true, true, true, true, true, false, true);

  private final SimModel model;
  private final TimeSource time;
  private final Universe universe;
  private final Map<String, Universe.Entry> simSymbols;

  public SimulatedMarketDataProvider(long seed, MarketCalendar calendar, Universe universe, SimScenarios scenarios,
                                     TimeSource time) {
    this.universe = universe;
    this.time = time;
    this.simSymbols = simSymbols();
    this.model = new SimModel(seed, calendar, this::entry, scenarios);
  }

  /** {@code SIMA}–{@code SIMZ}: always fractionable, MEGA tier (§15.7 item 8). */
  static Map<String, Universe.Entry> simSymbols() {
    Map<String, Universe.Entry> out = new LinkedHashMap<>();
    for (char c = 'A'; c <= 'Z'; c++) {
      String sym = "SIM" + c;
      out.put(sym, new Universe.Entry(sym, "Simulated Test Stock " + c, "NASDAQ", AssetType.COMMON_STOCK,
          LiquidityTier.MEGA, null, true));
    }
    return out;
  }

  Optional<Universe.Entry> entry(String symbol) {
    if (symbol == null) {
      return Optional.empty();
    }
    Universe.Entry sim = simSymbols.get(symbol);
    return sim != null ? Optional.of(sim) : universe.get(symbol);
  }

  SimModel model() {
    return model;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Capabilities capabilities() {
    return CAPABILITIES;
  }

  @Override
  public List<Asset> assets() {
    List<Asset> out = new ArrayList<>();
    universe.entries().forEach(e -> out.add(e.asset()));
    simSymbols.values().forEach(e -> out.add(e.asset()));
    return out;
  }

  @Override
  public Map<String, AssetType> assetTypes() {
    return assets().stream().collect(Collectors.toMap(Asset::symbol, Asset::type, (a, b) -> a));
  }

  // ------------------------------------------------------------------------------------------- point in time

  @Override
  public boolean knows(String symbol) {
    return entry(symbol).isPresent();
  }

  @Override
  public Optional<Quote> quoteAt(String symbol, Instant t) {
    return model.params(symbol).flatMap(p -> model.quoteAt(p, t));
  }

  @Override
  public Optional<Trade> lastTradeAt(String symbol, Instant t) {
    return model.params(symbol).flatMap(p -> model.lastTradeAt(p, t));
  }

  @Override
  public List<Trade> tradesBetween(String symbol, Instant fromExclusive, Instant toInclusive, int max) {
    return model.params(symbol).map(p -> model.tradesBetween(p, fromExclusive, toInclusive, max)).orElse(List.of());
  }

  @Override
  public Optional<BigDecimal> officialClose(String symbol, LocalDate tradeDate) {
    return model.params(symbol).flatMap(p -> model.officialClose(p, tradeDate));
  }

  @Override
  public Optional<BigDecimal> priceAt(String symbol, Instant t) {
    return model.params(symbol).flatMap(p -> model.priceAt(p, t));
  }

  @Override
  public Optional<DayStats> dayStats(String symbol, LocalDate tradeDate, Instant upTo) {
    return model.params(symbol).flatMap(p -> model.dayStats(p, tradeDate, upTo));
  }

  @Override
  public Optional<Bar> minuteBar(String symbol, Instant minuteStart, Instant upTo) {
    return model.params(symbol).flatMap(p -> model.minuteBar(p, minuteStart, upTo));
  }

  @Override
  public Instant confirmedAt(String symbol, Instant now) {
    return model.scenarios().blackoutSince(symbol, now).orElse(now);
  }

  @Override
  public boolean halted(String symbol, Instant t) {
    return model.scenarios().halted(symbol, t);
  }

  // ------------------------------------------------------------------------------------------- provider SPI

  @Override
  public Map<String, Snapshot> snapshots(Set<String> symbols) {
    Instant now = time.now();
    Map<String, Snapshot> out = new LinkedHashMap<>();
    for (String s : symbols) {
      model.params(s).ifPresent(p -> out.put(s, snapshot(p, now)));
    }
    return out;
  }

  private Snapshot snapshot(SimModel.Params p, Instant now) {
    MarketCalendar cal = model.calendar();
    LocalDate day = cal.currentPnlDay(now);
    LocalDate prev = cal.previousTradingDay(day);
    Bar daily = model.dailyBar(p, day, now).orElseGet(() -> model.dailyBar(p, prev, now).orElse(null));
    Bar prevDaily = daily != null && Et.date(daily.start()).equals(day)
        ? model.dailyBar(p, prev, now).orElse(null)
        : model.dailyBar(p, cal.previousTradingDay(prev), now).orElse(null);
    Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
    return new Snapshot(p.symbol(), model.lastTradeAt(p, now).orElse(null), model.quoteAt(p, now).orElse(null),
        model.minuteBar(p, minute, now).orElse(null), daily, prevDaily, now);
  }

  @Override
  public Map<String, Quote> latestQuotes(Set<String> symbols) {
    Instant now = time.now();
    Map<String, Quote> out = new LinkedHashMap<>();
    symbols.forEach(s -> quoteAt(s, now).ifPresent(q -> out.put(s, q)));
    return out;
  }

  @Override
  public Map<String, Trade> latestTrades(Set<String> symbols) {
    Instant now = time.now();
    Map<String, Trade> out = new LinkedHashMap<>();
    symbols.forEach(s -> lastTradeAt(s, now).ifPresent(t -> out.put(s, t)));
    return out;
  }

  @Override
  public Map<String, List<Bar>> bars(BarQuery q) {
    Instant now = time.now();
    Instant to = q.to() == null || q.to().isAfter(now) ? now : q.to();
    Map<String, List<Bar>> out = new LinkedHashMap<>();
    for (String s : new TreeSet<>(q.symbols())) {
      Optional<SimModel.Params> p = model.params(s);
      if (p.isEmpty()) {
        continue;
      }
      List<Bar> bars = isIntraday(q.tf()) ? intradayBars(p.get(), q, to) : dailyBars(p.get(), q, to);
      if (q.adj() != AdjustmentMode.RAW) {
        bars = adjust(p.get(), bars, to);
      }
      if (bars.size() > q.limit() && q.limit() > 0) {
        bars = bars.subList(bars.size() - q.limit(), bars.size());
      }
      out.put(s, List.copyOf(bars));
    }
    return out;
  }

  static boolean isIntraday(Timeframe tf) {
    return switch (tf) {
      case MIN1, MIN5, MIN15, MIN30, HOUR1 -> true;
      default -> false;
    };
  }

  private List<Bar> intradayBars(SimModel.Params p, BarQuery q, Instant to) {
    List<Bar> minutes = new ArrayList<>();
    MarketCalendar cal = model.calendar();
    LocalDate first = Et.date(q.from());
    LocalDate last = Et.date(to);
    for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
      Optional<TradingDay> day = cal.day(d);
      if (day.isEmpty()) {
        continue;
      }
      Instant start = q.extendedHours() ? day.get().preOpenAt() : day.get().rthOpenAt();
      Instant end = q.extendedHours() ? day.get().postCloseAt() : day.get().rthCloseAt();
      if (start.isBefore(q.from())) {
        start = q.from().truncatedTo(ChronoUnit.MINUTES);
      }
      if (end.isAfter(to)) {
        end = to;
      }
      if (!end.isAfter(start)) {
        continue;
      }
      // One pass over the prints of the window, bucketed by minute.
      List<Trade> trades = model.tradesBetween(p, start.minusNanos(1), end, Integer.MAX_VALUE);
      Map<Instant, List<Trade>> byMinute = new LinkedHashMap<>();
      for (Trade t : trades) {
        byMinute.computeIfAbsent(t.ts().truncatedTo(ChronoUnit.MINUTES), k -> new ArrayList<>()).add(t);
      }
      byMinute.forEach((m, ts) -> SimModel.aggregate(p, Timeframe.MIN1, m, ts).ifPresent(minutes::add));
    }
    return q.tf() == Timeframe.MIN1 ? minutes : BarMath.aggregate(minutes, q.tf());
  }

  private List<Bar> dailyBars(SimModel.Params p, BarQuery q, Instant to) {
    List<Bar> days = new ArrayList<>();
    LocalDate from = Et.date(q.from());
    for (LocalDate d : model.tradingDays(from, Et.date(to))) {
      model.dailyBar(p, d, to).ifPresent(days::add);
    }
    return q.tf() == Timeframe.DAY1 ? days : BarMath.aggregate(days, q.tf());
  }

  /** Split-adjusts bars dated before a split's ex-date (§14: {@code Rounding.SPLIT_ADJUST}). */
  private List<Bar> adjust(SimModel.Params p, List<Bar> bars, Instant asOf) {
    SimScenarios sc = model.scenarios();
    if (!sc.hasSplits(p.symbol())) {
      return bars;
    }
    LocalDate today = Et.date(asOf);
    List<Bar> out = new ArrayList<>(bars.size());
    for (Bar b : bars) {
      LocalDate d = Et.date(b.start());
      BigDecimal oldR = sc.rawFactorNumerator(p.symbol(), d).divide(sc.rawFactorNumerator(p.symbol(), today), 12,
          RoundingMode.HALF_EVEN);
      BigDecimal newR = sc.rawFactorDenominator(p.symbol(), d).divide(sc.rawFactorDenominator(p.symbol(), today), 12,
          RoundingMode.HALF_EVEN);
      if (oldR.compareTo(newR) == 0) {
        out.add(b);
        continue;
      }
      out.add(new Bar(b.symbol(), b.tf(), b.start(), Rounding.splitAdjust(b.open(), oldR, newR),
          Rounding.splitAdjust(b.high(), oldR, newR), Rounding.splitAdjust(b.low(), oldR, newR),
          Rounding.splitAdjust(b.close(), oldR, newR), b.volume(),
          b.vwap() == null ? null : Rounding.splitAdjust(b.vwap(), oldR, newR), b.tradeCount(), b.partialVolume()));
    }
    return out;
  }

  @Override
  public List<CorporateActionData> corporateActions(Set<String> symbols, LocalDate from, LocalDate to) {
    return model.scenarios().corporateActions(symbols, from, to);
  }

  @Override
  public Optional<Fundamentals> fundamentals(String symbol) {
    Optional<SimModel.Params> po = model.params(symbol);
    if (po.isEmpty()) {
      return Optional.empty();
    }
    SimModel.Params p = po.get();
    Instant now = time.now();
    LocalDate today = model.calendar().currentPnlDay(now);
    BigDecimal price = model.priceAt(p, now).orElse(BigDecimal.ONE);
    BigDecimal hi = price;
    BigDecimal lo = price;
    for (LocalDate d : model.tradingDays(today.minusDays(365), today)) {
      Optional<BigDecimal> c = model.officialClose(p, d);
      if (c.isPresent()) {
        hi = hi.max(c.get());
        lo = lo.min(c.get());
      }
    }
    long k = SimMath.mix(p.key(), 201);
    BigDecimal shares = BigDecimal.valueOf(StrictMath.round(p.advShares() * (150 + 250 * SimMath.uniform(k))));
    BigDecimal pe = SimMath.decimal6(10 + 35 * SimMath.uniform(SimMath.mix(k, 1))).setScale(2, RoundingMode.HALF_EVEN);
    BigDecimal eps = price.divide(pe, 2, RoundingMode.HALF_EVEN);
    BigDecimal yield = SimMath.decimal6(0.03 * SimMath.uniform(SimMath.mix(k, 2))).setScale(4, RoundingMode.HALF_EVEN);
    boolean etf = p.type() == AssetType.ETF;
    return Optional.of(new Fundamentals(symbol, etf ? null : price.multiply(shares).setScale(0, RoundingMode.HALF_EVEN),
        etf ? null : pe, etf ? null : eps, yield, hi, lo,
        SimMath.decimal6(p.beta()).setScale(2, RoundingMode.HALF_EVEN), etf ? null : shares,
        etf ? "Exchange Traded Fund" : "Simulated", null, ID));
  }

  /**
   * §15.10: simulated analyst recommendation trends for stocks (ETFs have none): the last four months, each a
   * deterministic function of {@code (seed, symbol, month)} drifting slowly, so the UI can be exercised in dev and
   * e2e. Labelled {@code simulated} like everything the SIM serves.
   */
  @Override
  public List<RecommendationTrend> recommendations(String symbol) {
    Optional<SimModel.Params> po = model.params(symbol);
    if (po.isEmpty() || po.get().type() == AssetType.ETF) {
      return List.of();
    }
    SimModel.Params p = po.get();
    LocalDate month = Et.date(time.now()).withDayOfMonth(1);
    long k = SimMath.mix(p.key(), 307);
    int analysts = 6 + (int) StrictMath.floor(SimMath.uniform(k) * 40);
    double tilt = SimMath.uniform(SimMath.mix(k, 1));                 // 0 bearish … 1 bullish
    List<RecommendationTrend> out = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      LocalDate period = month.minusMonths(i);
      long km = SimMath.mix(k, period.getYear() * 12L + period.getMonthValue());
      double t = StrictMath.min(1, StrictMath.max(0, tilt + 0.08 * (SimMath.uniform(km) - 0.5)));
      int n = analysts + (int) StrictMath.floor(SimMath.uniform(SimMath.mix(km, 1)) * 3);
      int strongBuy = (int) StrictMath.round(n * 0.35 * t);
      int buy = (int) StrictMath.round(n * 0.40 * t);
      int sell = (int) StrictMath.round(n * 0.12 * (1 - t));
      int strongSell = (int) StrictMath.round(n * 0.05 * (1 - t));
      int hold = StrictMath.max(0, n - strongBuy - buy - sell - strongSell);
      out.add(new RecommendationTrend(symbol, period, strongBuy, buy, hold, sell, strongSell));
    }
    return out;
  }

  /** §15.10 screener statistics: the SIM's shares outstanding (stocks only); no industry label (sectors are static). */
  @Override
  public Optional<CompanyProfile> profile(String symbol, RefreshPriority priority) {
    return fundamentals(symbol).filter(f -> f.sharesOutstanding() != null)
        .map(f -> new CompanyProfile(symbol, f.sharesOutstanding(), f.marketCap(), null, ID));
  }

  @Override
  public Movers movers(MoverList list, int top) {
    Instant now = time.now();
    LocalDate day = model.calendar().currentPnlDay(now);
    LocalDate prev = model.calendar().previousTradingDay(day);
    List<Mover> all = new ArrayList<>();
    for (Universe.Entry e : universe.entries()) {
      if (e.type() == AssetType.ETF) {
        continue;
      }
      Optional<SimModel.Params> p = model.params(e.symbol());
      if (p.isEmpty()) {
        continue;
      }
      Optional<BigDecimal> last = model.priceAt(p.get(), now);
      Optional<BigDecimal> prevClose = model.officialClose(p.get(), prev);
      if (last.isEmpty() || prevClose.isEmpty() || prevClose.get().signum() == 0) {
        continue;
      }
      BigDecimal change = last.get().subtract(prevClose.get());
      BigDecimal pct = change.divide(prevClose.get(), 10, RoundingMode.HALF_EVEN);
      long volume = model.dayStats(p.get(), day, now).map(DayStats::volume).orElse(0L);
      all.add(new Mover(e.symbol(), last.get(), change, pct, volume));
    }
    Comparator<Mover> order = switch (list) {
      case GAINERS -> Comparator.comparing(Mover::changePct).reversed();
      case LOSERS -> Comparator.comparing(Mover::changePct);
      case ACTIVES -> Comparator.comparing((Mover m) -> m.volume() == null ? 0L : m.volume()).reversed();
    };
    List<Mover> items = all.stream()
        .filter(m -> list != MoverList.GAINERS || m.changePct().signum() > 0)
        .filter(m -> list != MoverList.LOSERS || m.changePct().signum() < 0)
        .sorted(order.thenComparing(Mover::symbol)).limit(Math.max(0, top)).toList();
    return new Movers(list, items, now, ID);
  }

  /** One deterministic "session recap" item per symbol and trading day (newest first). */
  @Override
  public List<NewsItem> news(Set<String> symbols, int limit, Instant before) {
    Instant now = before == null || before.isAfter(time.now()) ? time.now() : before;
    List<String> syms = symbols.isEmpty() ? List.of("SPY", "QQQ", "AAPL", "NVDA", "MSFT")
        : symbols.stream().sorted().toList();
    List<NewsItem> out = new ArrayList<>();
    MarketCalendar cal = model.calendar();
    LocalDate d = cal.tradingDayOnOrBefore(Et.date(now));
    for (int i = 0; i < 10 && out.size() < limit * 2; i++, d = cal.previousTradingDay(d)) {
      Optional<TradingDay> day = cal.day(d);
      if (day.isEmpty() || day.get().rthCloseAt().isAfter(now)) {
        continue;
      }
      for (String s : syms) {
        Optional<SimModel.Params> p = model.params(s);
        Optional<BigDecimal> close = p.flatMap(x -> model.officialClose(x, day.get().tradeDate()));
        Optional<BigDecimal> prev = p.flatMap(x -> model.officialClose(x, cal.previousTradingDay(day.get().tradeDate())));
        if (close.isEmpty() || prev.isEmpty() || prev.get().signum() == 0) {
          continue;
        }
        BigDecimal pct = close.get().subtract(prev.get()).multiply(BigDecimal.valueOf(100))
            .divide(prev.get(), 2, RoundingMode.HALF_EVEN);
        String move = pct.signum() >= 0 ? "rose " + pct.toPlainString() : "fell " + pct.negate().toPlainString();
        String id = "sim-" + s.toLowerCase(Locale.ROOT) + "-" + day.get().tradeDate();
        out.add(new NewsItem(id, s + " " + move + "% to $" + close.get().setScale(2, RoundingMode.HALF_EVEN)
            .toPlainString() + " in simulated trading",
            "Session recap generated by the SIM market for " + day.get().tradeDate() + ". Not real news.",
            "https://stocks.invalid/sim-news/" + id, ID, List.of(s), day.get().rthCloseAt().plusSeconds(300), null));
      }
    }
    return out.stream().sorted(Comparator.comparing(NewsItem::publishedAt).reversed().thenComparing(NewsItem::id))
        .limit(Math.max(0, limit)).toList();
  }
}
