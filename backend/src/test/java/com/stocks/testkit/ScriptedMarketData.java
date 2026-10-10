package com.stocks.testkit;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.MoverList;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.BarRequest;
import com.stocks.contract.marketdata.BarSeries;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.marketdata.LeaseHandle;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.marketdata.MarkBasis;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.MarketDataStatus;
import com.stocks.contract.marketdata.MarketUpdateListener;
import com.stocks.contract.marketdata.Print;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.contract.marketdata.model.Movers;
import com.stocks.contract.marketdata.model.NewsItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link MarketDataService} test double that plays a script of exact quotes, prints, closes and halts at exact
 * instants (§26). Everything is evaluated against the injected {@link TimeSource} (a {@code MutableTimeSource}), so a
 * test moves time and sees exactly the market it scripted; nothing is random and nothing touches the network.
 * Test sources only; the main-code boot default is {@code NoopMarketDataService} (§27.1).
 *
 * <pre>{@code
 * ScriptedMarketData md = new ScriptedMarketData(time)
 *     .withAsset("AAPL")
 *     .prevClose("AAPL", "225.00")
 *     .quote("AAPL", START, "227.40", 300, "227.42", 200)
 *     .print("AAPL", START.plusMillis(400), "227.41", 100);
 * }</pre>
 *
 * Semantics: {@link #quote(String)} composes the latest quote and print at or before now; {@link #executableQuote}
 * returns the latest quote confirmed in {@code [notBefore, now]} (anti-look-ahead, §12.3); {@link #drainPrints}
 * returns each print with {@code observedAt <= now} once; {@link #requestRefresh} is recorded and calls every
 * registered {@link MarketUpdateListener} synchronously; {@link #mark} is the last print, else the quote mid, else the
 * previous close. Bars ({@link #scriptBar}), movers, news and fundamentals are empty unless scripted.
 */
public class ScriptedMarketData implements MarketDataService {

  public static final String SOURCE = "scripted";

  private record ScriptedQuote(Instant at, BigDecimal bid, long bidSize, BigDecimal ask, long askSize,
                               DataQuality quality) {}

  private record Halt(Instant from, Instant toOrNull) {
    boolean covers(Instant t) {
      return !t.isBefore(from) && (toOrNull == null || t.isBefore(toOrNull));
    }
  }

  /** A refresh request seen by the double. */
  public record RefreshRequest(String symbol, RefreshPriority priority, Instant at) {}

  private final TimeSource time;
  private final Map<String, Asset> assets = new ConcurrentHashMap<>();
  private final Map<String, List<ScriptedQuote>> quotes = new ConcurrentHashMap<>();
  private final Map<String, List<Print>> prints = new ConcurrentHashMap<>();
  private final Map<String, Integer> drained = new ConcurrentHashMap<>();
  private final Map<String, BigDecimal> prevCloses = new ConcurrentHashMap<>();
  private final Map<String, Map<LocalDate, BigDecimal>> dayEndMarks = new ConcurrentHashMap<>();
  private final Map<String, List<Halt>> halts = new ConcurrentHashMap<>();
  private final Map<String, Fundamentals> fundamentals = new ConcurrentHashMap<>();
  private final Map<String, List<Bar>> scriptedBars = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> leases = new ConcurrentHashMap<>();
  private final List<MarketUpdateListener> listeners = new CopyOnWriteArrayList<>();
  private final List<RefreshRequest> refreshRequests = new CopyOnWriteArrayList<>();

  public ScriptedMarketData(TimeSource time) {
    this.time = time;
  }

  // ------------------------------------------------------------------------------------------- script

  /** A tradable, fractionable MEGA-tier common stock. */
  public ScriptedMarketData withAsset(String symbol) {
    return withAsset(new Asset(norm(symbol), norm(symbol) + " Inc.", "NASDAQ", AssetType.COMMON_STOCK, true, true, true,
        true, false, LiquidityTier.MEGA, true));
  }

  public ScriptedMarketData withAsset(Asset asset) {
    assets.put(norm(asset.symbol()), asset);
    return this;
  }

  /** A quote observed (and confirmed) at {@code at}, quality REALTIME_CONSOLIDATED. */
  public ScriptedMarketData quote(String symbol, Instant at, String bid, long bidSize, String ask, long askSize) {
    return quote(symbol, at, bid, bidSize, ask, askSize, DataQuality.REALTIME_CONSOLIDATED);
  }

  public ScriptedMarketData quote(String symbol, Instant at, String bid, long bidSize, String ask, long askSize,
                                  DataQuality quality) {
    List<ScriptedQuote> list = quotes.computeIfAbsent(norm(symbol), k -> new CopyOnWriteArrayList<>());
    list.add(new ScriptedQuote(at, dec(bid), bidSize, dec(ask), askSize, quality));
    list.sort((a, b) -> a.at().compareTo(b.at()));
    return this;
  }

  /** A consolidated print at {@code at} (exchange time = observed time). */
  public ScriptedMarketData print(String symbol, Instant at, String price, long size) {
    List<Print> list = prints.computeIfAbsent(norm(symbol), k -> new CopyOnWriteArrayList<>());
    list.add(new Print(dec(price), size, at, at, true));
    list.sort((a, b) -> a.observedAt().compareTo(b.observedAt()));
    return this;
  }

  public ScriptedMarketData prevClose(String symbol, String price) {
    prevCloses.put(norm(symbol), dec(price));
    return this;
  }

  public ScriptedMarketData dayEndMark(String symbol, LocalDate tradeDate, String price) {
    dayEndMarks.computeIfAbsent(norm(symbol), k -> new ConcurrentHashMap<>()).put(tradeDate, dec(price));
    return this;
  }

  /** Halted from {@code from} until {@code toOrNull} (exclusive; null = until further notice). */
  public ScriptedMarketData halt(String symbol, Instant from, Instant toOrNull) {
    halts.computeIfAbsent(norm(symbol), k -> new CopyOnWriteArrayList<>()).add(new Halt(from, toOrNull));
    return this;
  }

  /** A bar that {@link #bars} returns for its symbol and timeframe when the request window covers its start. */
  public ScriptedMarketData scriptBar(Bar bar) {
    scriptedBars.computeIfAbsent(norm(bar.symbol()), k -> new CopyOnWriteArrayList<>()).add(bar);
    return this;
  }

  public ScriptedMarketData fundamentals(Fundamentals f) {
    fundamentals.put(norm(f.symbol()), f);
    return this;
  }

  public ScriptedMarketData addListener(MarketUpdateListener listener) {
    listeners.add(listener);
    return this;
  }

  /** Calls every listener for {@code symbol}, as the {@code QuoteBook} does on new data. */
  public void publish(String symbol) {
    listeners.forEach(l -> l.onMarketUpdate(norm(symbol)));
  }

  public List<RefreshRequest> refreshRequests() {
    return List.copyOf(refreshRequests);
  }

  /** Current leases: symbol → owner keys. */
  public Map<String, Set<String>> leases() {
    Map<String, Set<String>> out = new HashMap<>();
    leases.forEach((s, owners) -> out.put(s, Set.copyOf(owners)));
    return out;
  }

  // ------------------------------------------------------------------------------------------- MarketDataService

  @Override
  public Optional<Asset> asset(String symbol) {
    return Optional.ofNullable(assets.get(norm(symbol)));
  }

  @Override
  public List<Asset> search(String query, int limit) {
    String q = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
    return assets.values().stream()
        .filter(a -> a.symbol().startsWith(q) || a.name().toUpperCase(Locale.ROOT).contains(q))
        .sorted((a, b) -> a.symbol().compareTo(b.symbol()))
        .limit(Math.max(0, limit))
        .toList();
  }

  @Override
  public QuoteView quote(String symbol) {
    String sym = norm(symbol);
    Instant now = time.now();
    Optional<ScriptedQuote> q = latestQuote(sym, now);
    Optional<Print> p = latestPrint(sym, now);
    BigDecimal prev = prevCloses.get(sym);
    if (q.isEmpty() && p.isEmpty() && prev == null) {
      return QuoteView.none(sym, SOURCE);
    }
    BigDecimal last = p.map(Print::price).orElse(null);
    BigDecimal change = last != null && prev != null ? last.subtract(prev) : null;
    BigDecimal changePct = change != null && prev.signum() != 0
        ? change.divide(prev, 10, RoundingMode.HALF_EVEN) : null;
    Instant asOf = latest(q.map(ScriptedQuote::at).orElse(null), p.map(Print::observedAt).orElse(null));
    DataQuality quality = q.map(ScriptedQuote::quality).orElse(p.isPresent()
        ? DataQuality.REALTIME_CONSOLIDATED : DataQuality.NONE);
    return new QuoteView(sym, q.map(ScriptedQuote::bid).orElse(null), q.map(ScriptedQuote::bidSize).orElse(null),
        q.map(ScriptedQuote::ask).orElse(null), q.map(ScriptedQuote::askSize).orElse(null), last,
        p.map(Print::size).orElse(null), p.map(Print::observedAt).orElse(null), prev, change, changePct, null, null,
        null, null, true, null, asOf, q.map(ScriptedQuote::at).orElse(null), quality, SOURCE, isHalted(sym));
  }

  @Override
  public Map<String, QuoteView> quotes(Collection<String> symbols) {
    Map<String, QuoteView> out = new LinkedHashMap<>();
    for (String s : symbols) {
      out.put(norm(s), quote(s));
    }
    return out;
  }

  @Override
  public Optional<ExecQuote> executableQuote(String symbol, Instant notBefore, SessionType session) {
    String sym = norm(symbol);
    Instant now = time.now();
    if (isHalted(sym)) {
      return Optional.empty();
    }
    return latestQuote(sym, now)
        .filter(q -> notBefore == null || !q.at().isBefore(notBefore))
        .map(q -> new ExecQuote(sym, q.bid(), q.bidSize(), q.ask(), q.askSize(),
            latestPrint(sym, now).map(Print::price).orElse(null), q.at(), q.at(), q.quality(), SOURCE, false));
  }

  @Override
  public synchronized List<Print> drainPrints(String symbol) {
    String sym = norm(symbol);
    List<Print> list = prints.getOrDefault(sym, List.of());
    Instant now = time.now();
    int from = drained.getOrDefault(sym, 0);
    List<Print> out = new ArrayList<>();
    int i = from;
    while (i < list.size() && !list.get(i).observedAt().isAfter(now)) {
      out.add(list.get(i));
      i++;
    }
    drained.put(sym, i);
    return out;
  }

  @Override
  public void requestRefresh(String symbol, RefreshPriority priority) {
    refreshRequests.add(new RefreshRequest(norm(symbol), priority, time.now()));
    publish(symbol);
  }

  @Override
  public Mark mark(String symbol) {
    String sym = norm(symbol);
    Instant now = time.now();
    Optional<Print> p = latestPrint(sym, now);
    if (p.isPresent()) {
      return new Mark(p.get().price(), DataQuality.REALTIME_CONSOLIDATED, p.get().observedAt(),
          MarkBasis.CONSOLIDATED_LAST);
    }
    Optional<ScriptedQuote> q = latestQuote(sym, now);
    if (q.isPresent()) {
      BigDecimal mid = q.get().bid().add(q.get().ask()).divide(BigDecimal.TWO, 4, RoundingMode.HALF_EVEN);
      return new Mark(mid, q.get().quality(), q.get().at(), MarkBasis.IEX_MID);
    }
    BigDecimal prev = prevCloses.get(sym);
    if (prev != null) {
      return new Mark(prev, DataQuality.STALE, null, MarkBasis.PREV_CLOSE);
    }
    return new Mark(null, DataQuality.NONE, null, null);
  }

  @Override
  public Optional<BigDecimal> previousClose(String symbol) {
    return Optional.ofNullable(prevCloses.get(norm(symbol)));
  }

  @Override
  public Optional<BigDecimal> dayEndMark(String symbol, LocalDate tradeDate) {
    return Optional.ofNullable(dayEndMarks.getOrDefault(norm(symbol), Map.of()).get(tradeDate));
  }

  @Override
  public BarSeries bars(BarRequest request) {
    Timeframe tf = request.timeframeOrNull() != null ? request.timeframeOrNull() : Timeframe.MIN1;
    AdjustmentMode adj = request.adjustment() != null ? request.adjustment() : AdjustmentMode.SPLIT;
    List<Bar> out = scriptedBars.getOrDefault(norm(request.symbol()), List.of()).stream()
        .filter(b -> b.tf() == tf)
        .filter(b -> request.startOrNull() == null || !b.start().isBefore(request.startOrNull()))
        .filter(b -> request.endOrNull() == null || b.start().isBefore(request.endOrNull()))
        .sorted((a, b) -> a.start().compareTo(b.start()))
        .toList();
    return new BarSeries(norm(request.symbol()), tf, adj, out, null, SOURCE);
  }

  @Override
  public Movers movers(MoverList list, int limit) {
    return new Movers(list, List.of(), time.now(), SOURCE);
  }

  @Override
  public List<NewsItem> news(Set<String> symbols, int limit, Instant before) {
    return List.of();
  }

  @Override
  public Optional<Fundamentals> fundamentals(String symbol) {
    return Optional.ofNullable(fundamentals.get(norm(symbol)));
  }

  @Override
  public LeaseHandle lease(String symbol, LeasePriority priority, String ownerKey) {
    String sym = norm(symbol);
    leases.computeIfAbsent(sym, k -> ConcurrentHashMap.newKeySet()).add(ownerKey);
    return () -> leases.getOrDefault(sym, Set.of()).remove(ownerKey);
  }

  @Override
  public void releaseOwner(String ownerKey) {
    leases.values().forEach(owners -> owners.remove(ownerKey));
  }

  @Override
  public boolean isHalted(String symbol) {
    Instant now = time.now();
    return halts.getOrDefault(norm(symbol), List.of()).stream().anyMatch(h -> h.covers(now));
  }

  @Override
  public MarketDataStatus status() {
    return new MarketDataStatus(SOURCE, List.of(), List.of(), List.of());
  }

  // ------------------------------------------------------------------------------------------- helpers

  private Optional<ScriptedQuote> latestQuote(String sym, Instant now) {
    ScriptedQuote best = null;
    for (ScriptedQuote q : quotes.getOrDefault(sym, List.of())) {
      if (!q.at().isAfter(now)) {
        best = q;
      }
    }
    return Optional.ofNullable(best);
  }

  private Optional<Print> latestPrint(String sym, Instant now) {
    Print best = null;
    for (Print p : prints.getOrDefault(sym, List.of())) {
      if (!p.observedAt().isAfter(now)) {
        best = p;
      }
    }
    return Optional.ofNullable(best);
  }

  private static Instant latest(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isAfter(b) ? a : b;
  }

  private static BigDecimal dec(String s) {
    return new BigDecimal(s);
  }

  private static String norm(String symbol) {
    return symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
  }
}
