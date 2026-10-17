package com.stocks.marketdata.core;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.SessionType;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.marketdata.MarketUpdateListener;
import com.stocks.contract.marketdata.Print;
import com.stocks.contract.marketdata.ProviderStatus;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.Tape;
import com.stocks.contract.marketdata.TapePrint;
import com.stocks.contract.marketdata.TapeSource;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.marketdata.config.ExecQuoteRules;
import com.stocks.marketdata.spi.Capabilities;
import com.stocks.marketdata.spi.PointInTimeMarket;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SIM feed: every read is computed from the {@link PointInTimeMarket} at {@code TimeSource.now()}, so it is exact
 * under a fixed test clock and identical to history. A ticker thread (250 ms) plays the role of the stream: it calls
 * the {@link MarketUpdateListener}s for leased symbols whose quote or last print changed, which is all the engine and
 * the STOMP fan-out need.
 */
public class SimFeed implements MarketFeed {

  private static final Logger log = LoggerFactory.getLogger(SimFeed.class);
  static final String SOURCE = "simulated";
  static final Duration TICK = Duration.ofMillis(250);
  static final Duration FIRST_DRAIN_LOOKBACK = Duration.ofSeconds(60);
  static final int MAX_PRINTS = 2_000;
  static final List<Duration> TAPE_LOOKBACKS = List.of(Duration.ofMinutes(2), Duration.ofMinutes(15),
      Duration.ofHours(2));

  private final PointInTimeMarket sim;
  private final MarketCalendar calendar;
  private final TimeSource time;
  private final ExecQuoteRules rules;
  private final ExecutableQuoteBuilder builder;
  private final QuoteComposer composer;
  private final Function<String, LiquidityTier> tiers;
  private final SubscriptionBudgetManager budget;
  private final Supplier<List<MarketUpdateListener>> listeners;
  private final Capabilities capabilities;
  private final Map<String, Instant> drainedUpTo = new ConcurrentHashMap<>();
  private final Map<String, String> lastNotified = new ConcurrentHashMap<>();
  private volatile Thread ticker;
  private volatile boolean running;
  private volatile Instant lastTickAt;

  public SimFeed(PointInTimeMarket sim, Capabilities capabilities, MarketCalendar calendar, TimeSource time,
                 ExecQuoteRules rules, Function<String, LiquidityTier> tiers, SubscriptionBudgetManager budget,
                 Supplier<List<MarketUpdateListener>> listeners) {
    this.sim = sim;
    this.capabilities = capabilities;
    this.calendar = calendar;
    this.time = time;
    this.rules = rules;
    this.builder = new ExecutableQuoteBuilder(rules);
    this.composer = new QuoteComposer(rules.regularConfirmed(), rules.extConfirmed());
    this.tiers = tiers;
    this.budget = budget;
    this.listeners = listeners;
  }

  @Override
  public void start() {
    running = true;
    ticker = Thread.ofPlatform().daemon(true).name("sim-ticker").start(this::tickLoop);
  }

  @Override
  public void close() {
    running = false;
    Thread t = ticker;
    if (t != null) {
      t.interrupt();
    }
  }

  private void tickLoop() {
    while (running) {
      try {
        tick();
      } catch (RuntimeException e) {
        log.warn("sim-ticker: {}", e.toString());
      }
      try {
        Thread.sleep(TICK.toMillis());
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  /** Notifies listeners for leased symbols whose quote, last print or halt state changed (package-private: tests). */
  void tick() {
    Instant now = time.now();
    lastTickAt = now;
    for (String symbol : budget.leasedSymbols()) {
      if (!sim.knows(symbol)) {
        continue;
      }
      Optional<Quote> q = sim.quoteAt(symbol, now);
      Optional<Trade> t = sim.lastTradeAt(symbol, now);
      String fingerprint = q.map(x -> x.ts().toString()).orElse("-") + "|" + t.map(x -> x.ts().toString()).orElse("-")
          + "|" + sim.halted(symbol, now);
      if (!fingerprint.equals(lastNotified.put(symbol, fingerprint))) {
        notifyListeners(symbol);
      }
    }
  }

  private void notifyListeners(String symbol) {
    for (MarketUpdateListener l : listeners.get()) {
      try {
        l.onMarketUpdate(symbol);
      } catch (RuntimeException e) {
        log.warn("MarketUpdateListener {} failed for {}: {}", l.getClass().getSimpleName(), symbol, e.toString());
      }
    }
  }

  // ------------------------------------------------------------------------------------------- reads

  @Override
  public QuoteView quote(String symbol) {
    if (!sim.knows(symbol)) {
      return QuoteView.none(symbol, SOURCE);
    }
    Instant now = time.now();
    return composer.compose(inputs(symbol, now), now, calendar.forSymbol(symbol), null);
  }

  private QuoteComposer.Inputs inputs(String symbol, Instant now) {
    return new QuoteComposer.Inputs(symbol, sim.quoteAt(symbol, now).orElse(null), sim.confirmedAt(symbol, now),
        sim.lastTradeAt(symbol, now).orElse(null), d -> closeIfDone(symbol, d, now),
        d -> sim.dayStats(symbol, d, now), false, sim.halted(symbol, now), true, true, SOURCE);
  }

  /** The official close of {@code d}, once its regular session has ended. */
  private Optional<BigDecimal> closeIfDone(String symbol, LocalDate d, Instant now) {
    Optional<TradingDay> day = calendar.forSymbol(symbol).day(d);    // a crypto day closes at 24:00 ET (§29)
    if (day.isEmpty() || now.isBefore(day.get().rthCloseAt())) {
      return Optional.empty();
    }
    return sim.officialClose(symbol, d);
  }

  @Override
  public Map<String, QuoteView> quotes(Collection<String> symbols) {
    Map<String, QuoteView> out = new LinkedHashMap<>();
    symbols.forEach(s -> out.put(s, quote(s)));
    return out;
  }

  /**
   * §15.10: the SIM's prints are a pure function of time, so the tape is the latest {@code limit} prints up to now
   * (looking back 2 minutes, then 15, then 2 hours for thin symbols), each colored against the SIM quote in force at
   * its time.
   */
  @Override
  public Tape tape(String symbol, int limit) {
    if (!sim.knows(symbol)) {
      return Tape.none(symbol);
    }
    Instant now = time.now();
    int n = Math.max(1, limit);
    List<Trade> trades = List.of();
    for (Duration lookback : TAPE_LOOKBACKS) {
      trades = sim.tradesBetween(symbol, now.minus(lookback), now, 50_000);
      if (trades.size() >= n) {
        break;
      }
    }
    List<TapePrint> out = new ArrayList<>(Math.min(n, trades.size()));
    for (int i = trades.size() - 1; i >= 0 && out.size() < n; i--) {
      Trade t = trades.get(i);
      Quote q = sim.quoteAt(symbol, t.ts()).orElse(null);
      out.add(new TapePrint(t.id(), t.price(), t.size(), t.ts(), t.venue(), true,
          TapeClassifier.classify(t.price(), q == null ? null : q.bid(), q == null ? null : q.ask())));
    }
    return new Tape(symbol, TapeSource.SIMULATED, true, out);
  }

  @Override
  public Optional<ExecQuote> executableQuote(String symbol, Instant notBefore, SessionType session) {
    if (!sim.knows(symbol)) {
      return Optional.empty();
    }
    Instant now = time.now();
    Optional<Trade> last = sim.lastTradeAt(symbol, now);
    List<Trade> recent = sim.tradesBetween(symbol, now.minus(Duration.ofSeconds(10)), now, 200);
    ExecutableQuoteBuilder.Inputs in = new ExecutableQuoteBuilder.Inputs(symbol, tiers.apply(symbol),
        sim.halted(symbol, now), sim.quoteAt(symbol, now).orElse(null), sim.confirmedAt(symbol, now), true, recent,
        last.orElse(null), null);
    return builder.build(in, now, notBefore, session);
  }

  @Override
  public List<Print> drainPrints(String symbol) {
    if (!sim.knows(symbol)) {
      return List.of();
    }
    Instant now = time.now();
    Instant from = drainedUpTo.getOrDefault(symbol, now.minus(FIRST_DRAIN_LOOKBACK));
    drainedUpTo.put(symbol, now);
    if (!now.isAfter(from)) {
      return List.of();
    }
    List<Print> out = new ArrayList<>();
    for (Trade t : sim.tradesBetween(symbol, from, now, MAX_PRINTS)) {
      if (t.eligible()) {
        out.add(new Print(t.price(), t.size(), t.ts(), t.observedAt(), true));
      }
    }
    return out;
  }

  @Override
  public void requestRefresh(String symbol, RefreshPriority priority) {
    notifyListeners(symbol);                        // the SIM is always current: answer at once
  }

  @Override
  public MarkInputs markInputs(String symbol) {
    Instant now = time.now();
    Optional<Trade> last = sim.lastTradeAt(symbol, now);
    Map<LocalDate, BigDecimal> closes = new TreeMap<>();
    MarketCalendar cal = calendar.forSymbol(symbol);
    LocalDate day = cal.currentPnlDay(now);
    for (LocalDate d : List.of(day, cal.previousTradingDay(day))) {
      closeIfDone(symbol, d, now).ifPresent(c -> closes.put(d, c));
    }
    return new MarkInputs(sim.quoteAt(symbol, now).orElse(null), sim.confirmedAt(symbol, now), last.orElse(null),
        last.orElse(null), closes, true);
  }

  @Override
  public boolean halted(String symbol) {
    return sim.halted(symbol, time.now());
  }

  @Override
  public Optional<Bar> currentMinuteBar(String symbol) {
    Instant now = time.now();
    return sim.minuteBar(symbol, now.truncatedTo(ChronoUnit.MINUTES), now);
  }

  @Override
  public List<ProviderStatus> providers() {
    Map<String, Object> probe = new LinkedHashMap<>();
    probe.put("note", "simulated market; no upstream calls");
    return List.of(new ProviderStatus(SOURCE, "LIVE", budget.leasedSymbols().size(), -1, 0, 0,
        lastTickAt, capabilities.asMap(), probe));
  }

  @Override
  public List<String> staleSymbols(Set<String> leased) {
    Instant now = time.now();
    if (calendar.stateAt(now).type() == SessionType.CLOSED) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String s : leased) {
      if (sim.knows(s) && Duration.between(sim.confirmedAt(s, now), now).compareTo(rules.regularConfirmed()) > 0) {
        out.add(s);
      }
    }
    return out;
  }

  @Override
  public Health health() {
    return Health.LIVE;
  }

  @Override
  public DataQuality nominalQuality() {
    return DataQuality.SIMULATED;
  }

  @Override
  public String liveWindowEt() {
    return null;
  }
}
