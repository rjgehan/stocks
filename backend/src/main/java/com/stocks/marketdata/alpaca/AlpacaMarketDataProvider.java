package com.stocks.marketdata.alpaca;

import com.stocks.common.domain.MoverList;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.CorporateActionData;
import com.stocks.contract.marketdata.model.MarketEvent;
import com.stocks.contract.marketdata.model.Mover;
import com.stocks.contract.marketdata.model.Movers;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Snapshot;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.contract.marketdata.model.TradingDayData;
import com.stocks.marketdata.config.MarketDataProperties;
import com.stocks.marketdata.core.WebSocketStream;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.marketdata.spi.Capabilities;
import com.stocks.marketdata.spi.MarketDataProvider;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Alpaca as a {@link MarketDataProvider} (§15.3): Basic plan = IEX quotes and trades live (30 stream symbols, one
 * connection), SIP bars at least 15 minutes old, assets, calendar, news, screener, corporate actions. On
 * {@code alpaca-sip} the feed is SIP: consolidated quotes, unlimited symbols, no embargo. The startup probe narrows the
 * capabilities ({@link #updateCapabilities}).
 */
public class AlpacaMarketDataProvider implements MarketDataProvider {

  /** The Basic plan's 15-minute SIP embargo plus a minute of clock skew. */
  static final Duration SIP_EMBARGO_CLAMP = Duration.ofMinutes(16);

  private final MarketDataProperties props;
  private final AlpacaRestClient rest;
  private final TimeSource time;
  private final WebSocketStream.Timings timings;
  private volatile Capabilities capabilities;
  private volatile int snapshotBatch;

  public AlpacaMarketDataProvider(MarketDataProperties props, AlpacaRestClient rest, TimeSource time,
                                  WebSocketStream.Timings timings) {
    this.props = props;
    this.rest = rest;
    this.time = time;
    this.timings = timings;
    boolean sip = "sip".equals(props.effectiveFeed());
    this.capabilities = new Capabilities(true, true, sip, sip ? 10_000 : props.alpaca().maxStreamSymbols(),
        props.alpaca().restPerMinute(), sip ? Duration.ZERO : Duration.ofMinutes(15),
        sip ? null : LocalTime.of(8, 0), sip ? null : LocalTime.of(17, 0),
        true, true, false, true, true, true, true, false);
    this.snapshotBatch = Math.max(1, props.alpaca().snapshotBatch());
  }

  public AlpacaRestClient rest() {
    return rest;
  }

  public String feed() {
    return props.effectiveFeed();
  }

  public int snapshotBatch() {
    return snapshotBatch;
  }

  public void setSnapshotBatch(int batch) {
    this.snapshotBatch = Math.max(1, batch);
  }

  public void updateCapabilities(Capabilities c) {
    this.capabilities = c;
  }

  @Override
  public String id() {
    return AlpacaRestClient.ID;
  }

  @Override
  public Capabilities capabilities() {
    return capabilities;
  }

  @Override
  public List<Asset> assets() {
    return rest.assets(RefreshPriority.BACKGROUND);
  }

  @Override
  public List<TradingDayData> calendar(LocalDate from, LocalDate to) {
    return rest.calendar(from, to, RefreshPriority.BACKGROUND);
  }

  @Override
  public Map<String, Snapshot> snapshots(Set<String> symbols) {
    return snapshots(symbols, RefreshPriority.UI);
  }

  /** Batched by {@code snapshot-batch} (100 by default; the probe may raise it). */
  @Override
  public Map<String, Snapshot> snapshots(Set<String> symbols, RefreshPriority priority) {
    Map<String, Snapshot> out = new LinkedHashMap<>();
    List<String> all = new ArrayList<>(new TreeSet<>(symbols));
    for (int i = 0; i < all.size(); i += snapshotBatch) {
      Set<String> batch = new TreeSet<>(all.subList(i, Math.min(all.size(), i + snapshotBatch)));
      out.putAll(rest.snapshots(batch, feed(), priority));
    }
    return out;
  }

  @Override
  public Map<String, Quote> latestQuotes(Set<String> symbols) {
    return rest.latestQuotes(symbols, feed(), RefreshPriority.ORDER_CRITICAL);
  }

  @Override
  public Map<String, Trade> latestTrades(Set<String> symbols) {
    return rest.latestTrades(symbols, feed(), RefreshPriority.ORDER_CRITICAL);
  }

  @Override
  public Map<String, List<Bar>> bars(BarQuery q) {
    return bars(q, RefreshPriority.UI);
  }

  /**
   * The Basic plan refuses SIP bars newer than 15 minutes (HTTP 403 "subscription does not permit querying recent SIP
   * data", even for an {@code end} in the future), so every {@code feed=sip} query is clamped here to
   * {@code end ≤ now − }{@link #SIP_EMBARGO_CLAMP}: daily marks, the split guard, asset sync and any future caller. A
   * daily bar whose start (00:00 ET) is at or before the clamped end is still returned. Empty when nothing is left.
   */
  @Override
  public Map<String, List<Bar>> bars(BarQuery q, RefreshPriority priority) {
    String feed = q.feed() != null ? q.feed() : "sip";
    Instant to = q.to();
    if ("sip".equalsIgnoreCase(feed) && !"sip".equals(feed())) {
      Instant latest = time.now().minus(SIP_EMBARGO_CLAMP);
      to = to == null || to.isAfter(latest) ? latest : to;
      if (q.from() != null && !q.from().isBefore(to)) {
        return Map.of();
      }
    }
    return rest.bars(new BarQuery(q.symbols(), q.tf(), q.from(), to, q.adj(), q.extendedHours(), q.limit(), feed),
        priority);
  }

  @Override
  public List<NewsItem> news(Set<String> symbols, int limit, Instant before) {
    // user-triggered extras ask below the quote poll, which keeps the last 20% of the shared budget (§15.5)
    return rest.news(symbols, limit, before, RefreshPriority.BACKGROUND);
  }

  @Override
  public List<CorporateActionData> corporateActions(Set<String> symbols, LocalDate from, LocalDate to) {
    return rest.corporateActions(symbols, from, to, RefreshPriority.BACKGROUND);
  }

  @Override
  public Movers movers(MoverList list, int top) {
    List<Mover> items = switch (list) {
      case GAINERS -> rest.movers(top, RefreshPriority.BACKGROUND).get("gainers");
      case LOSERS -> rest.movers(top, RefreshPriority.BACKGROUND).get("losers");
      case ACTIVES -> rest.mostActives(top, RefreshPriority.BACKGROUND);
    };
    return new Movers(list, items, time.now(), id());
  }

  @Override
  public AlpacaStreamClient openStream(Consumer<MarketEvent> sink) {
    return openStream(sink, () -> true);
  }

  public AlpacaStreamClient openStream(Consumer<MarketEvent> sink, BooleanSupplier venueHours) {
    String url = props.alpaca().streamUrl();
    if ("sip".equals(feed()) && url.endsWith("/iex")) {
      url = url.substring(0, url.length() - 4) + "/sip";
    }
    AlpacaStreamClient stream = new AlpacaStreamClient(URI.create(url), props.alpaca().keyId(),
        props.alpaca().secretKey(), feed(), time, timings, venueHours, sink);
    stream.start();
    return stream;
  }
}
