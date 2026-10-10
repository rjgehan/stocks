package com.stocks.config.stubs;

import com.stocks.common.domain.ChartRange;
import com.stocks.common.domain.DataQuality;
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
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.MarketDataStatus;
import com.stocks.contract.marketdata.Print;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.contract.marketdata.model.Movers;
import com.stocks.contract.marketdata.model.NewsItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Day-0 market-data default (§27.1): a market where nothing is known. Every quote has {@code quality=NONE} and no
 * prices, no symbol exists, there are no bars, prints, movers or news, nothing is halted, and leases are accepted and
 * ignored. It lets the app boot and the UI render before WS2's SIM provider lands; WS2 deletes it with the real
 * {@code MarketDataServiceImpl}. Tests use the test kit's {@code ScriptedMarketData} instead.
 */
public class NoopMarketDataService implements MarketDataService, ContractStub {

  /** {@code source} on everything this service returns. */
  public static final String SOURCE = "none";

  private static final LeaseHandle NO_LEASE = () -> { };

  private final TimeSource timeSource;

  public NoopMarketDataService(TimeSource timeSource) {
    this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
  }

  @Override
  public String stubOwner() {
    return "WS2";
  }

  @Override
  public Optional<Asset> asset(String symbol) {
    return Optional.empty();
  }

  @Override
  public List<Asset> search(String query, int limit) {
    return List.of();
  }

  @Override
  public QuoteView quote(String symbol) {
    return QuoteView.none(normalize(symbol), SOURCE);
  }

  @Override
  public Map<String, QuoteView> quotes(Collection<String> symbols) {
    Map<String, QuoteView> out = new LinkedHashMap<>();
    for (String s : symbols) {
      String sym = normalize(s);
      out.put(sym, QuoteView.none(sym, SOURCE));
    }
    return out;
  }

  @Override
  public Optional<ExecQuote> executableQuote(String symbol, Instant notBefore, SessionType session) {
    return Optional.empty();
  }

  @Override
  public List<Print> drainPrints(String symbol) {
    return List.of();
  }

  @Override
  public void requestRefresh(String symbol, RefreshPriority priority) {
    // nothing to refresh
  }

  @Override
  public Mark mark(String symbol) {
    return new Mark(null, DataQuality.NONE, null, null);
  }

  @Override
  public Optional<BigDecimal> previousClose(String symbol) {
    return Optional.empty();
  }

  @Override
  public Optional<BigDecimal> dayEndMark(String symbol, LocalDate tradeDate) {
    return Optional.empty();
  }

  @Override
  public BarSeries bars(BarRequest request) {
    Timeframe tf = request.timeframeOrNull() != null ? request.timeframeOrNull()
        : defaultTimeframe(request.rangeOrNull());
    AdjustmentMode adj = request.adjustment() != null ? request.adjustment() : AdjustmentMode.SPLIT;
    return new BarSeries(normalize(request.symbol()), tf, adj, List.of(), null, SOURCE);
  }

  @Override
  public Movers movers(MoverList list, int limit) {
    return new Movers(list, List.of(), timeSource.now(), SOURCE);
  }

  @Override
  public List<NewsItem> news(Set<String> symbols, int limit, Instant before) {
    return List.of();
  }

  @Override
  public Optional<Fundamentals> fundamentals(String symbol) {
    return Optional.empty();
  }

  @Override
  public LeaseHandle lease(String symbol, LeasePriority priority, String ownerKey) {
    return NO_LEASE;
  }

  @Override
  public void releaseOwner(String ownerKey) {
    // no leases are held
  }

  @Override
  public boolean isHalted(String symbol) {
    return false;
  }

  @Override
  public MarketDataStatus status() {
    return new MarketDataStatus(SOURCE, List.of(), List.of(), List.of());
  }

  @Override
  public String toString() {
    return "NoopMarketDataService";
  }

  private static String normalize(String symbol) {
    return symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
  }

  /** §15.5 chart-range defaults; {@code 1Day} when neither a range nor a timeframe was given. */
  private static Timeframe defaultTimeframe(ChartRange range) {
    if (range == null) {
      return Timeframe.DAY1;
    }
    return switch (range) {
      case D1 -> Timeframe.MIN1;
      case D5 -> Timeframe.MIN5;
      case M1 -> Timeframe.MIN30;
      case M3, M6, YTD, Y1 -> Timeframe.DAY1;
      case Y5 -> Timeframe.WEEK1;
      case ALL -> Timeframe.MONTH1;
    };
  }
}
