package com.stocks.marketdata.config;

import com.stocks.common.domain.SessionType;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.MarketUpdateListener;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.SplitAdjustments;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.marketdata.alpaca.AlpacaCapabilityProbe;
import com.stocks.marketdata.alpaca.AlpacaMarketDataProvider;
import com.stocks.marketdata.alpaca.AlpacaRestClient;
import com.stocks.marketdata.alpaca.AlpacaStreamClient;
import com.stocks.marketdata.core.AssetRepository;
import com.stocks.marketdata.core.AssetService;
import com.stocks.marketdata.core.AssetSync;
import com.stocks.marketdata.core.BarService;
import com.stocks.marketdata.core.CorporateActionHolds;
import com.stocks.marketdata.core.QuoteSplitAdjuster;
import com.stocks.marketdata.core.DailyMarkRepository;
import com.stocks.marketdata.core.DailyMarkServiceImpl;
import com.stocks.marketdata.core.FundamentalsService;
import com.stocks.marketdata.core.LiveFeed;
import com.stocks.marketdata.core.LogoService;
import com.stocks.marketdata.core.MarkService;
import com.stocks.marketdata.core.MarketDataServiceImpl;
import com.stocks.marketdata.core.MarketFeed;
import com.stocks.marketdata.core.MoversService;
import com.stocks.marketdata.core.NewsService;
import com.stocks.marketdata.core.ProviderHttp;
import com.stocks.marketdata.core.ProviderRateLimiter;
import com.stocks.marketdata.core.QuoteBook;
import com.stocks.marketdata.core.RecommendationService;
import com.stocks.marketdata.core.ScreenerService;
import com.stocks.marketdata.core.ScreenerStatRepository;
import com.stocks.marketdata.core.ScreenerStats;
import com.stocks.marketdata.core.Sectors;
import com.stocks.marketdata.core.SimFeed;
import com.stocks.marketdata.core.SplitAdjustmentsImpl;
import com.stocks.marketdata.core.SplitGuard;
import com.stocks.marketdata.core.SubscriptionBudgetManager;
import com.stocks.marketdata.core.TrackedSymbols;
import com.stocks.marketdata.core.Universe;
import com.stocks.marketdata.core.WebSocketStream;
import com.stocks.marketdata.finnhub.FinnhubProvider;
import com.stocks.marketdata.finnhub.FinnhubRestClient;
import com.stocks.marketdata.sim.SimScenarios;
import com.stocks.marketdata.sim.SimulatedMarketDataProvider;
import com.stocks.marketdata.spi.MarketDataProvider;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything behind {@code MarketDataService} for one market-data profile (§15.4), built in one place so the wiring is
 * readable and testable without Spring:
 * <ul>
 *   <li>{@code simulated}: the SIM provider (§15.7) behind a {@link SimFeed}; every capability, quality
 *       {@code SIMULATED}.</li>
 *   <li>{@code hybrid}: Alpaca (IEX stream ≤ 30 symbols, REST snapshots, SIP history, assets, calendar, news, screener,
 *       logos) plus Finnhub when {@code FINNHUB_TOKEN} is set (fundamentals, security types, the SIP last-trade stream,
 *       news fallback), behind a {@link LiveFeed}. Without Alpaca keys nothing is streamed and REST calls fail: quotes
 *       stay {@code NONE}/{@code STALE} and the feed reports {@code DOWN}, but the app runs (§15.9).</li>
 *   <li>{@code alpaca-sip}: like {@code hybrid} with {@code feed=sip}; Finnhub only for fundamentals and types.</li>
 * </ul>
 * Listeners ({@link MarketUpdateListener}: the engine, WS5's {@code QuoteFanout}) are attached at start, after the
 * context is built, so neither side depends on the other at construction.
 */
public final class MarketDataRuntime implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(MarketDataRuntime.class);

  /** Collaborators owned by other workstreams, resolved lazily (their beans may depend on market data). */
  public record External(Supplier<Map<String, LocalDate>> holdsSource, SplitGuard.Flagger splitFlagger) {
    public static External none() {
      return new External(Map::of, d -> { });
    }
  }

  private final MarketDataProperties props;
  private final TimeSource time;
  private final MarketCalendar calendar;
  private final Universe universe;
  private final SubscriptionBudgetManager budget;
  private final AssetService assets;
  private final DailyMarkRepository dailyMarks;
  private final TrackedSymbols tracked;
  private final SplitAdjustments splits;
  private final CorporateActionHolds holds;
  private final ExecQuoteRules rules;
  private final MarketDataProvider primary;
  private final SimulatedMarketDataProvider sim;
  private final AlpacaMarketDataProvider alpaca;
  private final ProviderHttp alpacaHttp;
  private final FinnhubProvider finnhub;
  private final MarketFeed feed;
  private final BarService bars;
  private final MarkService marks;
  private final MoversService movers;
  private final NewsService news;
  private final FundamentalsService fundamentals;
  private final RecommendationService recommendations;
  private final ScreenerStats screenerStats;
  private final ScreenerService screener;
  private final LogoService logos;
  private final AssetSync assetSync;
  private final DailyMarkServiceImpl dailyMarkService;
  private final SplitGuard splitGuard;
  private final AlpacaCapabilityProbe probe;
  private final MarketDataServiceImpl service;
  private volatile List<MarketUpdateListener> listeners = List.of();
  private volatile boolean started;

  public MarketDataRuntime(MarketDataProperties props, ExecQuoteRules rules, DataSource dataSource, TimeSource time,
                           MarketCalendar calendar, Universe universe, SplitAdjustments splits, External external) {
    this.props = props;
    this.rules = rules;
    this.time = time;
    this.calendar = calendar;
    this.universe = universe;
    this.splits = splits;
    this.budget = new SubscriptionBudgetManager(time);
    this.assets = new AssetService(new AssetRepository(dataSource), universe, time);
    this.dailyMarks = new DailyMarkRepository(dataSource);
    this.tracked = new TrackedSymbols(dataSource);
    this.holds = new CorporateActionHolds(external.holdsSource(), time);
    Supplier<List<MarketUpdateListener>> listenerSupplier = () -> listeners;
    props.pinned().forEach(s -> budget.lease(s, com.stocks.contract.marketdata.LeasePriority.PINNED, "pin:" + s));

    List<Asset> seed;
    if (props.simulated()) {
      sim = new SimulatedMarketDataProvider(props.sim().seed(), calendar, universe,
          SimScenarios.load(props.sim().scenarios()), time);
      primary = sim;
      alpaca = null;
      alpacaHttp = null;
      finnhub = null;
      feed = new SimFeed(sim, sim.capabilities(), calendar, time, rules, assets::tier, budget, listenerSupplier);
      seed = sim.assets();
      fundamentals = new FundamentalsService(sim);
      recommendations = new RecommendationService(sim, time);
      news = new NewsService(List.of(sim), () -> true);
      logos = new LogoService(null);
      probe = null;
    } else {
      sim = null;
      MarketDataProperties.Alpaca a = props.alpaca();
      alpacaHttp = new ProviderHttp(AlpacaRestClient.ID, a.connectTimeout(), a.readTimeout(),
          new ProviderRateLimiter(AlpacaRestClient.ID, a.restPerMinute(), 0), time::now,
          Map.of("APCA-API-KEY-ID", a.keyId(), "APCA-API-SECRET-KEY", a.secretKey()));
      AlpacaRestClient rest = new AlpacaRestClient(a, alpacaHttp, time::now);
      alpaca = new AlpacaMarketDataProvider(props, rest, time, WebSocketStream.Timings.defaults());
      primary = alpaca;
      MarketDataProperties.Finnhub f = props.finnhub();
      ProviderHttp finnhubHttp = null;
      if (f.hasToken()) {
        finnhubHttp = new ProviderHttp(FinnhubRestClient.ID, a.connectTimeout(), a.readTimeout(),
            new ProviderRateLimiter(FinnhubRestClient.ID, f.restPerMinute(), 30), time::now,
            Map.of("X-Finnhub-Token", f.token()));
        finnhub = new FinnhubProvider(f, new FinnhubRestClient(f, finnhubHttp), time,
            WebSocketStream.Timings.defaults());
      } else {
        finnhub = null;
      }
      boolean sip = "sip".equals(props.effectiveFeed());
      BooleanSupplier alpacaVenue = sip ? this::inSession : () -> inWindow(LocalTime.of(8, 0), LocalTime.of(17, 0));
      LiveFeed.StreamFactory alpacaStreams = a.hasKeys() ? sink -> alpaca.openStream(sink, alpacaVenue) : null;
      LiveFeed.StreamFactory finnhubStreams = finnhub != null && f.streamEnabled() && !sip
          ? sink -> finnhub.openStream(sink, this::inSession) : null;
      QuoteBook book = new QuoteBook(time, listenerSupplier);
      LiveFeed live = new LiveFeed(props, alpaca, alpacaHttp, alpacaStreams, finnhubHttp, finnhubStreams, book, budget,
          calendar, time, rules, assets::tier, universe::symbols);
      feed = live;
      seed = universe.entries().stream().map(Universe.Entry::asset).toList();
      fundamentals = new FundamentalsService(finnhub);
      recommendations = new RecommendationService(finnhub, time);
      List<MarketDataProvider> newsSources = new ArrayList<>();
      newsSources.add(alpaca);
      if (finnhub != null) {
        newsSources.add(finnhub);
      }
      news = new NewsService(newsSources, () -> true);
      // BACKGROUND: a logo never takes the last 20% of the Alpaca budget from the quote poll (§15.5)
      logos = new LogoService(symbol -> rest.logo(symbol, RefreshPriority.BACKGROUND));
      probe = new AlpacaCapabilityProbe(alpaca, alpacaHttp, a.dataUrl(), a.tradingUrl(),
          () -> live.alpacaStream() instanceof AlpacaStreamClient c ? c : null, live::putProbe, time);
      if (!a.hasKeys()) {
        live.putProbe("keys", "missing: set ALPACA_KEY_ID and ALPACA_SECRET_KEY");
      }
    }
    this.bars = new BarService(primary, !props.simulated(), feed, calendar, time);
    this.marks = new MarkService(feed, calendar, time, dailyMarks, splits, holds::exDate, rules.regularConfirmed(),
        rules.extConfirmed());
    QuoteSplitAdjuster splitAdjuster = new QuoteSplitAdjuster(splits, calendar, time);
    // §15.10 screener: statistics from the primary's daily bars (SIP and, on hybrid, IEX) and company profiles from
    // Finnhub (or the SIM); none without a profile source
    boolean iexBasis = !props.simulated() && !"sip".equals(props.effectiveFeed());
    this.screenerStats = new ScreenerStats(new ScreenerStatRepository(dataSource),
        new ScreenerStats.Sources(primary, props.simulated() ? null : "sip", iexBasis,
            props.simulated() ? sim : finnhub, props.simulated()),
        universe::symbols, s -> universe.get(s).map(Universe.Entry::type).orElse(null), calendar, time,
        props.screener().profilesPerMinute());
    this.screener = new ScreenerService(feed, assets, Sectors.load(), screenerStats, splitAdjuster::adjust,
        splits instanceof SplitAdjustmentsImpl impl ? impl::shareFactor : ScreenerService.SplitFactor.NONE, time,
        props.simulated());
    this.movers = new MoversService(primary, feed, assets, calendar, time,
        () -> probe == null || probe.screenerAvailable(), splitAdjuster);
    this.assetSync = new AssetSync(assets, primary, finnhub, props.simulated(), seed, tracked, calendar, time);
    this.dailyMarkService = new DailyMarkServiceImpl(primary, sim, feed, dailyMarks, calendar, time);
    this.splitGuard = new SplitGuard(primary, props.simulated() ? null : "sip", tracked,
        splits instanceof SplitAdjustmentsImpl impl ? impl : null, external.splitFlagger(), calendar, time);
    this.service = new MarketDataServiceImpl(props.profile(), feed, assets, bars, marks, movers, news, fundamentals,
        budget, dailyMarks, splits, holds, splitAdjuster, calendar, time, sim, recommendations, screener);
    log.info("Market data: profile={} feed={} finnhub={} alpaca={}", props.profile(),
        props.simulated() ? "SIM" : props.effectiveFeed(), finnhub != null ? "on" : "off",
        props.simulated() ? "-" : props.alpaca());
  }

  /** Attaches the listeners, seeds the asset table and starts the feed (idempotent). */
  public synchronized void start(List<MarketUpdateListener> updateListeners) {
    if (started) {
      return;
    }
    started = true;
    this.listeners = List.copyOf(updateListeners);
    assetSync.seed();
    if (sim == null) {
      loadOfficialCloses();
    }
    feed.start();
  }

  /**
   * Live profiles: the quote book learns the stored official closes of the last two trading days, so quotes have the
   * official {@code prevClose} right after a restart (snapshots only carry IEX daily bars).
   */
  void loadOfficialCloses() {
    try {
      java.time.LocalDate day = calendar.currentPnlDay(time.now());
      Set<String> symbols = officialCloseSymbols();
      for (java.time.LocalDate d : List.of(calendar.previousTradingDay(day), day)) {
        dailyMarks.officialCloses(d, symbols).forEach((s, c) -> feed.officialClose(s, d, c));
      }
    } catch (RuntimeException e) {
      log.warn("Could not load official closes: {}", e.toString());
    }
  }

  /** Live boot work that needs the network: the probe and, when the table has no provider rows yet, an asset sync. */
  public void bootSync() {
    if (probe == null) {
      return;
    }
    try {
      probe.probe();
    } catch (RuntimeException e) {
      log.warn("Capability probe failed: {}", e.toString());
    }
    try {
      if (assets.countBySource(primary.id()) == 0) {
        assetSync.sync();
      }
    } catch (RuntimeException e) {
      log.warn("Boot asset sync failed: {}", e.toString());
    }
  }

  @Override
  public synchronized void close() {
    feed.close();
    started = false;
  }

  public boolean isStarted() {
    return started;
  }

  /** Symbols for official closes: held, watched, the universe and the pinned ones (SPY). */
  public Set<String> officialCloseSymbols() {
    Set<String> s = new TreeSet<>(universe.symbols());
    s.addAll(dayEndSymbols());
    return s;
  }

  /** Symbols for day-end marks: held, watched and pinned ones (SPY, the benchmark). */
  public Set<String> dayEndSymbols() {
    Set<String> s = new TreeSet<>(tracked.held());
    s.addAll(tracked.watched());
    s.addAll(props.pinned());
    s.add("SPY");
    return s;
  }

  private boolean inSession() {
    return calendar.stateAt(time.now()).type() != SessionType.CLOSED;
  }

  private boolean inWindow(LocalTime open, LocalTime close) {
    Instant now = time.now();
    if (!calendar.isTradingDay(Et.date(now))) {
      return false;
    }
    LocalTime t = Et.time(now);
    return !t.isBefore(open) && t.isBefore(close);
  }

  // ------------------------------------------------------------------------------------------- accessors

  /**
   * A corporate action was applied or flagged (§14): drop the hold and split-adjustment caches so every reader sees
   * the committed state at once instead of after their TTL.
   */
  public void corporateActionsChanged() {
    holds.invalidate();
    if (splits instanceof SplitAdjustmentsImpl impl) {
      impl.invalidate();
    }
  }

  public MarketDataProperties props() {
    return props;
  }

  public MarketDataServiceImpl service() {
    return service;
  }

  public DailyMarkServiceImpl dailyMarkService() {
    return dailyMarkService;
  }

  public MarketFeed feed() {
    return feed;
  }

  public MarketDataProvider primary() {
    return primary;
  }

  public SimulatedMarketDataProvider simOrNull() {
    return sim;
  }

  public AlpacaMarketDataProvider alpacaOrNull() {
    return alpaca;
  }

  public FinnhubProvider finnhubOrNull() {
    return finnhub;
  }

  public AlpacaCapabilityProbe probeOrNull() {
    return probe;
  }

  public AssetService assets() {
    return assets;
  }

  public AssetSync assetSync() {
    return assetSync;
  }

  public BarService bars() {
    return bars;
  }

  public MarkService marks() {
    return marks;
  }

  public MoversService movers() {
    return movers;
  }

  public ScreenerStats screenerStats() {
    return screenerStats;
  }

  public ScreenerService screener() {
    return screener;
  }

  public LogoService logos() {
    return logos;
  }

  public DailyMarkRepository dailyMarks() {
    return dailyMarks;
  }

  public SplitGuard splitGuard() {
    return splitGuard;
  }

  public SubscriptionBudgetManager budget() {
    return budget;
  }

  public TrackedSymbols tracked() {
    return tracked;
  }

  public Universe universe() {
    return universe;
  }

  public MarketCalendar calendar() {
    return calendar;
  }

  public ExecQuoteRules rules() {
    return rules;
  }
}
