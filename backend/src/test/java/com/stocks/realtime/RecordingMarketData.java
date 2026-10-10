package com.stocks.realtime;

import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.BarRequest;
import com.stocks.contract.marketdata.BarSeries;
import com.stocks.contract.marketdata.LeaseHandle;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.testkit.ScriptedMarketData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ScriptedMarketData} that also records lease priorities (open leases by {@code symbol|owner}), counts quote
 * reads and serves scripted bars, for the realtime tests.
 */
public class RecordingMarketData extends ScriptedMarketData {

  /** One open lease. */
  public record Lease(String symbol, LeasePriority priority, String owner) {}

  private final Map<String, Lease> open = new ConcurrentHashMap<>();
  private final List<String> released = new CopyOnWriteArrayList<>();
  private final Map<String, List<Bar>> bars = new ConcurrentHashMap<>();
  private final AtomicInteger quoteReads = new AtomicInteger();
  private final List<NewsItem> news = new CopyOnWriteArrayList<>();
  private final Map<String, com.stocks.contract.marketdata.Tape> tapes = new ConcurrentHashMap<>();
  private final Map<String, com.stocks.contract.marketdata.Recommendations> recommendations =
      new ConcurrentHashMap<>();

  public RecordingMarketData(TimeSource time) {
    super(time);
  }

  @Override
  public LeaseHandle lease(String symbol, LeasePriority priority, String ownerKey) {
    super.lease(symbol, priority, ownerKey);
    String key = symbol + "|" + ownerKey;
    open.put(key, new Lease(symbol, priority, ownerKey));
    return () -> {
      open.remove(key);
      released.add(key);
    };
  }

  @Override
  public void releaseOwner(String ownerKey) {
    super.releaseOwner(ownerKey);
    open.keySet().removeIf(k -> k.endsWith("|" + ownerKey));
  }

  @Override
  public com.stocks.contract.marketdata.QuoteView quote(String symbol) {
    quoteReads.incrementAndGet();
    return super.quote(symbol);
  }

  public RecordingMarketData withNews(NewsItem... items) {
    news.addAll(List.of(items));
    return this;
  }

  @Override
  public List<NewsItem> news(java.util.Set<String> symbols, int limit, java.time.Instant before) {
    return news.stream().filter(n -> symbols == null || symbols.isEmpty()
            || n.symbols().stream().anyMatch(symbols::contains))
        .limit(Math.max(0, limit)).toList();
  }

  /** §15.10: the tape {@code timeAndSales} serves for the symbol (newest first, as the real feeds return it). */
  public RecordingMarketData withTape(com.stocks.contract.marketdata.Tape tape) {
    tapes.put(tape.symbol(), tape);
    return this;
  }

  @Override
  public com.stocks.contract.marketdata.Tape timeAndSales(String symbol, int limit) {
    com.stocks.contract.marketdata.Tape t = tapes.get(symbol);
    if (t == null) {
      return com.stocks.contract.marketdata.Tape.none(symbol);
    }
    return new com.stocks.contract.marketdata.Tape(t.symbol(), t.source(), t.streaming(),
        t.prints().stream().limit(Math.max(0, limit)).toList());
  }

  public RecordingMarketData withRecommendations(com.stocks.contract.marketdata.Recommendations r) {
    recommendations.put(r.symbol(), r);
    return this;
  }

  @Override
  public Optional<com.stocks.contract.marketdata.Recommendations> recommendations(String symbol) {
    return Optional.ofNullable(recommendations.get(symbol));
  }

  public RecordingMarketData withBars(String symbol, Bar... list) {
    bars.put(symbol, new ArrayList<>(List.of(list)));
    return this;
  }

  @Override
  public BarSeries bars(BarRequest request) {
    List<Bar> list = bars.get(request.symbol());
    if (list == null) {
      return super.bars(request);
    }
    return new BarSeries(request.symbol(), request.timeframeOrNull(),
        request.adjustment() == null ? AdjustmentMode.SPLIT : request.adjustment(), List.copyOf(list), null, SOURCE);
  }

  public Optional<Lease> lease(String symbol, String owner) {
    return Optional.ofNullable(open.get(symbol + "|" + owner));
  }

  public Map<String, Lease> openLeases() {
    return Map.copyOf(open);
  }

  public List<String> released() {
    return List.copyOf(released);
  }

  public int quoteReads() {
    return quoteReads.get();
  }
}
