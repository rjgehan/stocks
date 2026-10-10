package com.stocks.marketdata.core;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.SessionType;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.contract.marketdata.Print;
import com.stocks.contract.marketdata.ProviderStatus;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.Tape;
import com.stocks.contract.marketdata.TapePrint;
import com.stocks.contract.marketdata.TapeSource;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.MarketEvent;
import com.stocks.contract.marketdata.model.Snapshot;
import com.stocks.marketdata.alpaca.AlpacaStreamClient;
import com.stocks.marketdata.alpaca.SizeSample;
import com.stocks.marketdata.config.ExecQuoteRules;
import com.stocks.marketdata.config.MarketDataProperties;
import com.stocks.marketdata.spi.ConnectionState;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import com.stocks.marketdata.spi.PointInTimeMarket.DayStats;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live feed (profiles {@code hybrid} and {@code alpaca-sip}, §15.4/§15.5): the Alpaca stream (IEX quotes, trades,
 * minute bars; ≤ 30 symbols chosen by the {@link SubscriptionBudgetManager}), the Finnhub SIP trade stream when a token
 * exists, and REST snapshots for everything else ({@code SnapshotPoller}: 2 s for order and viewing leases, 10 s for
 * holdings and lists, 60 s for the movers universe during sessions; {@code QuoteRefresher}: one in-flight request per
 * symbol on demand). Everything lands in the {@link QuoteBook}. If Alpaca is unreachable the app still runs: quotes
 * degrade to {@code STALE}/{@code NONE} and {@link #health()} reports {@code DOWN} (§15.9).
 */
public class LiveFeed implements MarketFeed {

  private static final Logger log = LoggerFactory.getLogger(LiveFeed.class);
  static final Duration LOOP = Duration.ofSeconds(1);
  static final Duration STREAMED_POLL = Duration.ofSeconds(60);
  static final Duration SYNC_REFRESH_MAX_AGE = Duration.ofSeconds(10);
  static final Duration CLOSED_REFRESH_MAX_AGE = Duration.ofMinutes(5);
  static final Duration REST_COOLDOWN = Duration.ofSeconds(30);
  static final Set<LeasePriority> HOT = EnumSet.of(LeasePriority.PINNED, LeasePriority.ORDER_TRIGGER,
      LeasePriority.ORDER_RESTING, LeasePriority.VIEWING);

  /** Opens a provider stream with a sink and a venue-hours predicate. */
  public interface StreamFactory {
    WebSocketStream open(Consumer<MarketEvent> sink);
  }

  private final MarketDataProperties props;
  private final MarketDataProvider alpaca;
  private final StreamFactory alpacaStreams;
  private final StreamFactory finnhubStreams;
  private final ProviderHttp alpacaHttp;
  private final ProviderHttp finnhubHttp;
  private final QuoteBook book;
  private final SubscriptionBudgetManager budget;
  private final MarketCalendar calendar;
  private final TimeSource time;
  private final ExecQuoteRules rules;
  private final ExecutableQuoteBuilder builder;
  private final QuoteComposer composer;
  private final Function<String, LiquidityTier> tiers;
  private final Supplier<Collection<String>> universe;
  private final QuoteComposer.Venue venue;
  private final ExecutorService refreshers = Executors.newVirtualThreadPerTaskExecutor();
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
  private final Map<String, Instant> lastPolled = new ConcurrentHashMap<>();
  private final Map<String, Instant> loggedErrors = new ConcurrentHashMap<>();
  private final Map<String, Object> probe = new ConcurrentHashMap<>();
  private volatile WebSocketStream alpacaStream;
  private volatile WebSocketStream finnhubStream;
  private final SizeSample restQuoteSizes = new SizeSample(256);
  private volatile boolean sizeUnitDecided;
  private volatile Instant universePolledAt;
  private volatile Instant lastRestSuccess;
  private volatile Instant lastRestFailure;
  private volatile boolean running;
  private Thread loop;

  public LiveFeed(MarketDataProperties props, MarketDataProvider alpaca, ProviderHttp alpacaHttp,
                  StreamFactory alpacaStreams, ProviderHttp finnhubHttp, StreamFactory finnhubStreams, QuoteBook book,
                  SubscriptionBudgetManager budget, MarketCalendar calendar, TimeSource time, ExecQuoteRules rules,
                  Function<String, LiquidityTier> tiers, Supplier<Collection<String>> universe) {
    this.props = props;
    this.alpaca = alpaca;
    this.alpacaHttp = alpacaHttp;
    this.alpacaStreams = alpacaStreams;
    this.finnhubHttp = finnhubHttp;
    this.finnhubStreams = finnhubStreams;
    this.book = book;
    this.budget = budget;
    this.calendar = calendar;
    this.time = time;
    this.rules = rules;
    this.builder = new ExecutableQuoteBuilder(rules);
    this.composer = new QuoteComposer(rules.regularConfirmed(), rules.extConfirmed());
    this.tiers = tiers;
    this.universe = universe;
    LocalTime open = alpaca.capabilities().venueOpenEt();
    this.venue = open == null ? null : new QuoteComposer.Venue(open, alpaca.capabilities().venueCloseEt());
  }

  public QuoteBook book() {
    return book;
  }

  public WebSocketStream alpacaStream() {
    return alpacaStream;
  }

  public void putProbe(String key, Object value) {
    probe.put(key, value == null ? "null" : value);
  }

  public Map<String, Object> probe() {
    return Map.copyOf(probe);
  }

  @Override
  public void start() {
    running = true;
    if (alpacaStreams != null) {
      alpacaStream = alpacaStreams.open(book::onEvent);
    }
    if (finnhubStreams != null) {
      finnhubStream = finnhubStreams.open(book::onEvent);
    }
    loop = Thread.ofPlatform().daemon(true).name("marketdata-live").start(this::run);
  }

  @Override
  public void close() {
    running = false;
    if (loop != null) {
      loop.interrupt();
    }
    WebSocketStream a = alpacaStream;
    if (a != null) {
      a.close();
    }
    WebSocketStream f = finnhubStream;
    if (f != null) {
      f.close();
    }
    refreshers.shutdownNow();
  }

  private void run() {
    while (running) {
      try {
        reconcile();
        poll();
      } catch (RuntimeException e) {
        logOnce("loop", "live feed loop: " + e);
      }
      try {
        Thread.sleep(LOOP.toMillis());
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  /**
   * The §15.8 quote-size unit check: once both sides have enough samples, the stream multiplier is set from the REST
   * (shares) and stream (raw) medians and published on {@code /admin/system}.
   */
  void detectQuoteSizeUnit() {
    if (sizeUnitDecided || !(alpacaStream instanceof AlpacaStreamClient a)) {
      return;
    }
    long m = SizeSample.multiplier(restQuoteSizes, a.rawQuoteSizes());
    if (m > 0) {
      sizeUnitDecided = true;
      a.setQuoteSizeMultiplier(m);
      putProbe("streamQuoteSizeUnit", m == 100 ? "round lots (x100)" : "shares (x1)");
      log.info("Alpaca stream quote sizes are {} (REST median {}, stream median {})",
          m == 100 ? "round lots" : "shares", restQuoteSizes.median(), a.rawQuoteSizes().median());
    }
  }

  /** Pushes the budget's choice to the streams (§15.5 reconciliation, every 1 s). */
  void reconcile() {
    detectQuoteSizeUnit();
    WebSocketStream a = alpacaStream;
    if (a != null) {
      // a 405 taught the stream a smaller limit: let the budget choose which symbols fit, by priority
      int max = Math.min(alpaca.capabilities().maxStreamSymbols(), a.symbolLimit());
      List<String> want = budget.select(max, null, a.subscriptionTimes());
      Set<String> set = new LinkedHashSet<>(want);
      a.setDesired(set, set, set);
    }
    WebSocketStream f = finnhubStream;
    if (f != null) {
      List<String> want = budget.select(props.finnhub().maxStreamSymbols(),
          SubscriptionBudgetManager.FINNHUB_PRIORITIES, f.subscriptionTimes());
      f.setDesired(new LinkedHashSet<>(want), Set.of(), Set.of());
    }
  }

  /** The snapshot poller (§15.5): due symbols by lease class, in batches. */
  void poll() {
    Instant now = time.now();
    Set<String> streamed = streamedQuotes();
    Map<String, LeasePriority> best = budget.bestPriorities();
    Set<String> engine = new TreeSet<>();
    Set<String> ui = new TreeSet<>();
    Set<String> background = new TreeSet<>();
    for (Map.Entry<String, LeasePriority> e : best.entrySet()) {
      String sym = e.getKey();
      Instant last = lastPolled.get(sym);
      Duration age = last == null ? Duration.ofDays(1) : Duration.between(last, now);
      if (streamed.contains(sym)) {
        if (age.compareTo(STREAMED_POLL) >= 0) {
          background.add(sym);                       // day bars and closes for streamed symbols
        }
      } else if (HOT.contains(e.getValue())) {
        if (age.compareTo(props.poll().hot()) >= 0) {
          (e.getValue() == LeasePriority.VIEWING ? ui : engine).add(sym);
        }
      } else if (age.compareTo(props.poll().warm()) >= 0) {
        ui.add(sym);
      }
    }
    SessionType session = calendar.stateAt(now).type();
    if (session != SessionType.CLOSED && (universePolledAt == null
        || Duration.between(universePolledAt, now).compareTo(props.poll().universe()) >= 0)) {
      universePolledAt = now;
      for (String s : universe.get()) {
        if (!engine.contains(s) && !ui.contains(s)) {
          background.add(s);
        }
      }
    }
    fetch(engine, RefreshPriority.ENGINE);
    fetch(ui, RefreshPriority.UI);
    fetch(background, RefreshPriority.BACKGROUND);
  }

  private Set<String> streamedQuotes() {
    WebSocketStream a = alpacaStream;
    return a == null ? Set.of() : a.subscribed();
  }

  /** Fetches snapshots and applies them; failures are logged once a minute, never thrown. */
  Map<String, Snapshot> fetch(Set<String> symbols, RefreshPriority priority) {
    if (symbols.isEmpty()) {
      return Map.of();
    }
    try {
      Map<String, Snapshot> snaps = alpaca.snapshots(symbols, priority);
      Instant done = time.now();
      lastRestSuccess = done;
      LocalDate day = calendar.currentPnlDay(done);
      Instant rthClose = calendar.day(day).map(TradingDay::rthCloseAt).orElse(null);
      for (String s : symbols) {
        lastPolled.put(s, done);
      }
      snaps.values().forEach(snap -> {
        if (snap.quote() != null) {
          restQuoteSizes.add(snap.quote().bidSize());
          restQuoteSizes.add(snap.quote().askSize());
        }
        book.applySnapshot(snap, done, day, rthClose);
      });
      return snaps;
    } catch (ProviderException e) {
      if (!e.throttled()) {
        lastRestFailure = time.now();
        logOnce("snap:" + e.status(), "snapshot poll failed (" + priority + "): " + e.getMessage());
      }
      return Map.of();
    } catch (RuntimeException e) {
      lastRestFailure = time.now();
      logOnce("snap:x", "snapshot poll failed (" + priority + "): " + e);
      return Map.of();
    }
  }

  /** After a failed REST call, request threads stop waiting on the provider for a while (circuit breaker). */
  private boolean restCoolingDown(Instant now) {
    Instant failed = lastRestFailure;
    Instant ok = lastRestSuccess;
    return failed != null && (ok == null || failed.isAfter(ok))
        && Duration.between(failed, now).compareTo(REST_COOLDOWN) < 0;
  }

  private void logOnce(String key, String message) {
    Instant now = time.now();
    Instant last = loggedErrors.get(key);
    if (last == null || Duration.between(last, now).toMinutes() >= 1) {
      loggedErrors.put(key, now);
      log.warn(message);
    }
  }

  // ------------------------------------------------------------------------------------------- reads

  @Override
  public QuoteView quote(String symbol) {
    return quotes(List.of(symbol)).get(symbol);
  }

  @Override
  public Map<String, QuoteView> quotes(Collection<String> symbols) {
    Instant now = time.now();
    if (!Thread.currentThread().getName().equals("engine-main") && !restCoolingDown(now)) {
      Set<String> due = new TreeSet<>();
      Set<String> streamed = streamedQuotes();
      Duration maxAge = calendar.stateAt(now).type() == SessionType.CLOSED ? CLOSED_REFRESH_MAX_AGE
          : SYNC_REFRESH_MAX_AGE;
      for (String s : symbols) {
        Instant last = lastPolled.get(s);
        boolean fresh = streamed.contains(s) && last != null
            || last != null && Duration.between(last, now).compareTo(maxAge) < 0;
        if (!fresh) {
          due.add(s);
        }
      }
      fetch(due, RefreshPriority.UI);
    }
    Map<String, QuoteView> out = new LinkedHashMap<>();
    for (String s : symbols) {
      out.put(s, compose(s, now));
    }
    return out;
  }

  @Override
  public Map<String, QuoteView> peek(Collection<String> symbols) {
    Instant now = time.now();
    Map<String, QuoteView> out = new LinkedHashMap<>();
    for (String s : symbols) {
      out.put(s, compose(s, now));
    }
    return out;
  }

  private QuoteView compose(String symbol, Instant now) {
    Optional<QuoteBook.View> v = book.view(symbol);
    if (v.isEmpty() || v.get().empty()) {
      return QuoteView.none(symbol, AlpacaSource.ID);
    }
    QuoteBook.View view = v.get();
    Instant confirmed = confirmedAt(symbol, view, null, now);
    QuoteComposer.Inputs in = new QuoteComposer.Inputs(symbol, view.quote(), confirmed, view.lastTrade(),
        view::close, d -> dayStats(view, d), !"sip".equals(props.effectiveFeed()), view.halted(), false,
        "sip".equals(props.effectiveFeed()), AlpacaSource.ID);
    return composer.compose(in, now, calendar, venue);
  }

  private static Optional<DayStats> dayStats(QuoteBook.View v, LocalDate d) {
    Bar b = v.dailyBar();
    if (b == null || !Et.date(b.start()).equals(d)) {
      return Optional.empty();
    }
    return Optional.of(new DayStats(b.open(), b.high(), b.low(), b.volume()));
  }

  /**
   * §12.3 confirmation: a symbol streamed on a live connection since at or before {@code notBefore} is confirmed up
   * to {@code min(now, lastMessageAt)}; otherwise by the last successful REST fetch, or the quote's own arrival.
   */
  private Instant confirmedAt(String symbol, QuoteBook.View view, Instant notBefore, Instant now) {
    Instant best = null;
    WebSocketStream a = alpacaStream;
    if (a != null && a.lastMessageAt() != null) {
      Instant since = notBefore != null ? notBefore : now;
      if (a.subscribedSince(since).contains(symbol)) {
        best = a.lastMessageAt().isAfter(now) ? now : a.lastMessageAt();
      }
    }
    best = later(best, view.polledAt());
    best = later(best, view.quoteObservedAt());
    return best;
  }

  private static Instant later(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isAfter(b) ? a : b;
  }

  /**
   * §15.10: the stream-delivered prints of the quote book. The source is that of the newest print (SIP when the
   * Finnhub stream or Alpaca SIP delivered it, IEX otherwise); with no print yet it is the stream that covers the
   * symbol, or {@code NONE}.
   */
  @Override
  public Tape tape(String symbol, int limit) {
    List<TapePrint> prints = book.tape(symbol, limit);
    WebSocketStream a = alpacaStream;
    WebSocketStream f = finnhubStream;
    boolean onFinnhub = f != null && f.subscribed().contains(symbol);
    boolean onAlpaca = a != null && a.subscribed().contains(symbol);
    boolean sip = "sip".equals(props.effectiveFeed());
    TapeSource source;
    if (!prints.isEmpty()) {
      source = prints.get(0).consolidated() ? TapeSource.SIP : TapeSource.IEX;
    } else if (onFinnhub || onAlpaca && sip) {
      source = TapeSource.SIP;
    } else if (onAlpaca) {
      source = TapeSource.IEX;
    } else {
      source = TapeSource.NONE;
    }
    return new Tape(symbol, source, onFinnhub || onAlpaca, prints);
  }

  /** §15.10: one background snapshot pass over the symbols not polled in the last minute (batches of 100). */
  @Override
  public void warm(Collection<String> symbols) {
    if (!running) {
      return;
    }
    Instant now = time.now();
    Set<String> due = new TreeSet<>();
    for (String s : symbols) {
      Instant last = lastPolled.get(s);
      if (last == null || Duration.between(last, now).compareTo(STREAMED_POLL) >= 0) {
        due.add(s);
      }
    }
    if (!due.isEmpty()) {
      refreshers.execute(() -> fetch(due, RefreshPriority.BACKGROUND));
    }
  }

  @Override
  public Optional<ExecQuote> executableQuote(String symbol, Instant notBefore, SessionType session) {
    Optional<QuoteBook.View> v = book.view(symbol);
    if (v.isEmpty()) {
      return Optional.empty();
    }
    Instant now = time.now();
    QuoteBook.View view = v.get();
    ExecutableQuoteBuilder.Inputs in = new ExecutableQuoteBuilder.Inputs(symbol, tiers.apply(symbol), view.halted(),
        view.quote(), confirmedAt(symbol, view, notBefore, now), "sip".equals(props.effectiveFeed()),
        view.recentConsolidated(), view.lastConsolidated(), view.lastIex());
    return builder.build(in, now, notBefore, session);
  }

  @Override
  public List<Print> drainPrints(String symbol) {
    return book.drainPrints(symbol);
  }

  /** Async; at most one request in flight per symbol; ends with listener callbacks from the book. */
  @Override
  public void requestRefresh(String symbol, RefreshPriority priority) {
    if (!running || !inFlight.add(symbol)) {
      return;
    }
    refreshers.execute(() -> {
      try {
        fetch(Set.of(symbol), priority);
      } finally {
        inFlight.remove(symbol);
      }
    });
  }

  @Override
  public MarkInputs markInputs(String symbol) {
    Optional<QuoteBook.View> v = book.view(symbol);
    if (v.isEmpty()) {
      return new MarkInputs(null, null, null, null, Map.of(), false);
    }
    QuoteBook.View view = v.get();
    return new MarkInputs(view.quote(), confirmedAt(symbol, view, null, time.now()), view.lastConsolidated(),
        view.lastTrade(), view.closes(), false);
  }

  @Override
  public boolean halted(String symbol) {
    return book.view(symbol).map(QuoteBook.View::halted).orElse(false);
  }

  @Override
  public Optional<Bar> currentMinuteBar(String symbol) {
    return book.view(symbol).map(QuoteBook.View::minuteBar);
  }

  @Override
  public void officialClose(String symbol, LocalDate date, BigDecimal close) {
    book.putOfficialClose(symbol, date, close);
  }

  @Override
  public List<ProviderStatus> providers() {
    List<ProviderStatus> out = new ArrayList<>();
    WebSocketStream a = alpacaStream;
    Map<String, Object> alpacaProbe = new HashMap<>(probe);
    if (a != null && a.detail() != null) {
      alpacaProbe.put("streamDetail", a.detail());
    }
    if (alpacaHttp != null && alpacaHttp.lastError() != null) {
      alpacaProbe.put("restLastError", alpacaHttp.lastError());
    }
    out.add(new ProviderStatus(AlpacaSource.ID, stateOf(a), a == null ? 0 : a.subscribed().size(),
        alpaca.capabilities().maxStreamSymbols(), alpacaHttp == null ? 0 : alpacaHttp.limiter().usedLastMinute(),
        alpacaHttp == null ? 0 : alpacaHttp.limiter().perMinute(), a == null ? null : a.lastMessageAt(),
        alpaca.capabilities().asMap(), alpacaProbe));
    WebSocketStream f = finnhubStream;
    if (finnhubHttp != null) {
      Map<String, Object> fp = new HashMap<>();
      if (f != null && f.detail() != null) {
        fp.put("streamDetail", f.detail());
      }
      if (finnhubHttp.lastError() != null) {
        fp.put("restLastError", finnhubHttp.lastError());
      }
      out.add(new ProviderStatus("finnhub", f == null ? "DISABLED" : stateOf(f), f == null ? 0 : f.subscribed().size(),
          props.finnhub().maxStreamSymbols(), finnhubHttp.limiter().usedLastMinute(), finnhubHttp.limiter().perMinute(),
          f == null ? null : f.lastMessageAt(), Map.of("fundamentals", true, "consolidatedTrades", f != null), fp));
    }
    return out;
  }

  private String stateOf(WebSocketStream s) {
    if (s == null) {
      return ConnectionState.DOWN.name();
    }
    ConnectionState st = s.state();
    if (st == ConnectionState.BACKOFF && lastRestSuccess == null) {
      return ConnectionState.DOWN.name();
    }
    return st.name();
  }

  @Override
  public List<String> staleSymbols(Set<String> leased) {
    Instant now = time.now();
    SessionType session = calendar.stateAt(now).type();
    if (session == SessionType.CLOSED || venue != null && !withinVenue(now)) {
      return List.of();
    }
    Duration threshold = session == SessionType.REGULAR ? rules.regularConfirmed() : rules.extConfirmed();
    List<String> out = new ArrayList<>();
    for (String s : leased) {
      Optional<QuoteBook.View> v = book.view(s);
      Instant c = v.map(view -> confirmedAt(s, view, null, now)).orElse(null);
      if (c == null || Duration.between(c, now).compareTo(threshold.multipliedBy(2)) > 0) {
        out.add(s);
      }
    }
    return out;
  }

  private boolean withinVenue(Instant now) {
    LocalTime et = Et.time(now);
    return !et.isBefore(venue.open()) && et.isBefore(venue.close());
  }

  @Override
  public Health health() {
    WebSocketStream a = alpacaStream;
    Instant now = time.now();
    boolean restOk = lastRestSuccess != null && Duration.between(lastRestSuccess, now).toMinutes() < 5;
    boolean streamLive = a != null && a.state() == ConnectionState.LIVE;
    if (streamLive && (restOk || lastRestSuccess == null)) {
      return Health.LIVE;
    }
    if (streamLive || restOk) {
      return Health.DEGRADED;
    }
    return Health.DOWN;
  }

  @Override
  public DataQuality nominalQuality() {
    return "sip".equals(props.effectiveFeed()) ? DataQuality.REALTIME_CONSOLIDATED : DataQuality.REALTIME_SINGLE_VENUE;
  }

  @Override
  public String liveWindowEt() {
    return venue == null ? null : venue.open() + "–" + venue.close();
  }

  /** The provider id used as {@code source} on live quotes. */
  static final class AlpacaSource {
    static final String ID = "alpaca";

    private AlpacaSource() {}
  }
}
