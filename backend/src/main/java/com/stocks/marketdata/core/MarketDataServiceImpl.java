package com.stocks.marketdata.core;

import com.stocks.common.domain.MoverList;
import com.stocks.common.domain.SessionType;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
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
import com.stocks.contract.marketdata.Recommendations;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.ScreenQuery;
import com.stocks.contract.marketdata.ScreenResult;
import com.stocks.contract.marketdata.Tape;
import com.stocks.contract.marketdata.SplitAdjustments;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.contract.marketdata.model.Movers;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.marketdata.spi.PointInTimeMarket;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@link MarketDataService} (§5.3, §15.5): one facade over the feed ({@link SimFeed} or {@link LiveFeed}), assets,
 * bars, marks, movers, news, fundamentals and the subscription budget. Symbols are normalized to upper case. A symbol
 * under a corporate-action hold (§14) is reported halted and gets no executable quote; quotes are split-adjusted
 * ({@link QuoteSplitAdjuster}).
 */
public class MarketDataServiceImpl implements MarketDataService {

  /** §15.10: the most Time &amp; Sales prints one call returns. */
  public static final int MAX_TAPE = 500;

  private final String profile;
  private final MarketFeed feed;
  private final AssetService assets;
  private final BarService bars;
  private final MarkService marks;
  private final MoversService movers;
  private final NewsService news;
  private final FundamentalsService fundamentals;
  private final SubscriptionBudgetManager budget;
  private final DailyMarkRepository dailyMarks;
  private final SplitAdjustments splits;
  private final CorporateActionHolds holds;
  private final QuoteSplitAdjuster splitAdjuster;
  private final MarketCalendar calendar;
  private final TimeSource time;
  private final PointInTimeMarket sim;
  private final RecommendationService recommendations;
  private final ScreenerService screener;

  public MarketDataServiceImpl(String profile, MarketFeed feed, AssetService assets, BarService bars, MarkService marks,
                               MoversService movers, NewsService news, FundamentalsService fundamentals,
                               SubscriptionBudgetManager budget, DailyMarkRepository dailyMarks,
                               SplitAdjustments splits, CorporateActionHolds holds,
                               QuoteSplitAdjuster splitAdjuster, MarketCalendar calendar,
                               TimeSource time, PointInTimeMarket simOrNull, RecommendationService recommendations,
                               ScreenerService screenerOrNull) {
    this.profile = profile;
    this.feed = feed;
    this.assets = assets;
    this.bars = bars;
    this.marks = marks;
    this.movers = movers;
    this.news = news;
    this.fundamentals = fundamentals;
    this.budget = budget;
    this.dailyMarks = dailyMarks;
    this.splits = splits;
    this.holds = holds;
    this.splitAdjuster = splitAdjuster;
    this.calendar = calendar;
    this.time = time;
    this.sim = simOrNull;
    this.recommendations = recommendations;
    this.screener = screenerOrNull;
  }

  /** Upper case, trimmed; null stays null. */
  public static String normalize(String symbol) {
    return symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
  }

  public MarketFeed feed() {
    return feed;
  }

  public String profile() {
    return profile;
  }

  @Override
  public Optional<Asset> asset(String symbol) {
    return assets.asset(normalize(symbol));
  }

  @Override
  public List<Asset> search(String query, int limit) {
    return assets.search(query, limit);
  }

  @Override
  public QuoteView quote(String symbol) {
    String s = normalize(symbol);
    if (s == null || s.isEmpty()) {
      return QuoteView.none(s, profile);
    }
    return withHold(splitAdjuster.adjust(feed.quote(s)));
  }

  @Override
  public Map<String, QuoteView> quotes(Collection<String> symbols) {
    Set<String> wanted = new LinkedHashSet<>();
    for (String s : symbols) {
      String n = normalize(s);
      if (n != null && !n.isEmpty()) {
        wanted.add(n);
      }
    }
    Map<String, QuoteView> out = new LinkedHashMap<>();
    feed.quotes(wanted).forEach((k, v) -> out.put(k, withHold(splitAdjuster.adjust(v))));
    return out;
  }

  /** A held symbol is shown halted (no fills, §12.6). */
  private QuoteView withHold(QuoteView v) {
    if (v == null || v.halted() || !holds.onHold(v.symbol())) {
      return v;
    }
    return new QuoteView(v.symbol(), v.bid(), v.bidSize(), v.ask(), v.askSize(), v.last(), v.lastSize(), v.lastAt(),
        v.prevClose(), v.change(), v.changePct(), v.dayOpen(), v.dayHigh(), v.dayLow(), v.volume(),
        v.volumeIsPartial(), v.extended(), v.asOf(), v.confirmedAt(), v.quality(), v.source(), true);
  }

  @Override
  public Optional<ExecQuote> executableQuote(String symbol, Instant notBefore, SessionType session) {
    String s = normalize(symbol);
    if (holds.onHold(s)) {
      return Optional.empty();
    }
    return feed.executableQuote(s, notBefore, session);
  }

  @Override
  public List<Print> drainPrints(String symbol) {
    return feed.drainPrints(normalize(symbol));
  }

  @Override
  public void requestRefresh(String symbol, RefreshPriority priority) {
    feed.requestRefresh(normalize(symbol), priority == null ? RefreshPriority.ENGINE : priority);
  }

  @Override
  public Mark mark(String symbol) {
    return marks.mark(normalize(symbol));
  }

  @Override
  public Optional<BigDecimal> previousClose(String symbol) {
    String s = normalize(symbol);
    Instant now = time.now();
    MarketCalendar calendar = this.calendar.forSymbol(s);            // a crypto pair: yesterday's 24:00 ET (§29)
    LocalDate today = calendar.tradeDateFor(now);
    LocalDate prev = calendar.previousTradingDay(calendar.currentPnlDay(now));
    // the stored official (SIP) close first: a live snapshot's prevDailyBar is the IEX-only daily bar
    BigDecimal close = safe(() -> dailyMarks.officialClose(s, prev)).orElse(null);
    if (close == null) {
      close = feed.markInputs(s).closes().get(prev);
    }
    if (close == null && sim != null) {
      close = sim.officialClose(s, prev).orElse(null);
    }
    return Optional.ofNullable(close).map(c -> splits.adjust(s, c, prev, today));
  }

  @Override
  public Optional<BigDecimal> dayEndMark(String symbol, LocalDate tradeDate) {
    String s = normalize(symbol);
    Optional<BigDecimal> stored = safe(() -> dailyMarks.dayEndMark(s, tradeDate));
    if (stored.isPresent() || sim == null) {
      return stored;
    }
    return DailyMarkServiceImpl.simDayEnd(sim, calendar, s, tradeDate, time.now());
  }

  @Override
  public BarSeries bars(BarRequest request) {
    BarRequest r = new BarRequest(normalize(request.symbol()), request.rangeOrNull(), request.timeframeOrNull(),
        request.startOrNull(), request.endOrNull(), request.limitOrNull(), request.adjustment(),
        request.extendedHours());
    return bars.bars(r);
  }

  @Override
  public Movers movers(MoverList list, int limit) {
    return movers.movers(list == null ? MoverList.GAINERS : list, Math.max(0, limit));
  }

  @Override
  public List<NewsItem> news(Set<String> symbols, int limit, Instant before) {
    Set<String> s = new TreeSet<>();
    if (symbols != null) {
      symbols.forEach(x -> {
        String n = normalize(x);
        if (n != null && !n.isEmpty()) {
          s.add(n);
        }
      });
    }
    return news.news(s, Math.max(0, limit), before);
  }

  @Override
  public Optional<Fundamentals> fundamentals(String symbol) {
    return fundamentals.fundamentals(normalize(symbol));
  }

  @Override
  public LeaseHandle lease(String symbol, LeasePriority priority, String ownerKey) {
    return budget.lease(normalize(symbol), priority, ownerKey);
  }

  @Override
  public void releaseOwner(String ownerKey) {
    budget.releaseOwner(ownerKey);
  }

  @Override
  public boolean isHalted(String symbol) {
    String s = normalize(symbol);
    return feed.halted(s) || holds.onHold(s);
  }

  @Override
  public boolean onCorporateActionHold(String symbol) {
    return holds.onHold(normalize(symbol));
  }

  @Override
  public MarketDataStatus status() {
    List<String> stale = new ArrayList<>(new TreeSet<>(feed.staleSymbols(budget.leasedSymbols())));
    return new MarketDataStatus(profile, stale, List.copyOf(new TreeSet<>(holds.symbols())), feed.providers());
  }

  /** §15.10: at most {@link #MAX_TAPE} prints; an unknown or blank symbol has no tape. */
  @Override
  public Tape timeAndSales(String symbol, int limit) {
    String s = normalize(symbol);
    if (s == null || s.isEmpty()) {
      return Tape.none(s);
    }
    return feed.tape(s, Math.max(1, Math.min(MAX_TAPE, limit)));
  }

  @Override
  public Optional<Recommendations> recommendations(String symbol) {
    String s = normalize(symbol);
    return s == null || s.isEmpty() || recommendations == null ? Optional.empty() : recommendations.recommendations(s);
  }

  /** §15.10: the screener over the universe ({@code NOT_IMPLEMENTED} without one). */
  @Override
  public ScreenResult screen(ScreenQuery query) {
    if (screener == null) {
      return MarketDataService.super.screen(query);
    }
    return screener.screen(query);
  }

  private static <T> Optional<T> safe(java.util.function.Supplier<Optional<T>> s) {
    try {
      return s.get();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
