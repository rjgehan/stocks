package com.stocks.marketdata.core;

import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.MarketUpdateListener;
import com.stocks.contract.marketdata.Print;
import com.stocks.contract.marketdata.TapePrint;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.BarEvent;
import com.stocks.contract.marketdata.model.MarketEvent;
import com.stocks.contract.marketdata.model.NewsEvent;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.QuoteEvent;
import com.stocks.contract.marketdata.model.Snapshot;
import com.stocks.contract.marketdata.model.StatusEvent;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.contract.marketdata.model.TradeEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live market state per symbol (§15.5), fed by the provider streams, the snapshot poller and the refresher. Every
 * update calls {@link MarketUpdateListener#onMarketUpdate} on every listener bean (the engine and WS5's
 * {@code QuoteFanout}), so {@code marketdata} depends on neither (§5.2 rule 7). Reads compose {@code QuoteView}s in
 * {@code QuoteComposer}; the SIM provider bypasses the book (it is a pure function of time).
 */
public class QuoteBook {

  private static final Logger log = LoggerFactory.getLogger(QuoteBook.class);
  static final int PRINT_BUFFER = 2_000;
  /** While consolidated prints arrive, IEX prints are not buffered for the engine (they are in the SIP already). */
  static final Duration CONSOLIDATED_DEDUP = Duration.ofSeconds(60);
  private static final int MAX_CLOSES = 8;
  /** §15.10: prints kept per symbol for Time &amp; Sales. */
  static final int TAPE_SIZE = 500;

  /** Mutable state of one symbol; guarded by its own monitor. */
  public static final class SymbolState {
    final String symbol;
    Quote quote;
    Instant quoteObservedAt;
    /** Completion time of the last successful REST fetch covering this symbol (§12.3 confirmation). */
    Instant polledAt;
    Trade lastIex;
    Trade lastConsolidated;
    Bar minuteBar;
    Bar dailyBar;
    final NavigableMap<LocalDate, BigDecimal> closes = new TreeMap<>();
    boolean halted;
    String haltReason;
    Instant updatedAt;
    final Deque<Trade> recentConsolidated = new ArrayDeque<>();
    final Deque<Print> prints = new ArrayDeque<>();
    /** Keys of the trades buffered recently, so a trade seen again (stream, then every snapshot poll) is one print. */
    final java.util.LinkedHashSet<String> bufferedKeys = new java.util.LinkedHashSet<>();
    Instant drainedUpTo;
    /** §15.10 Time &amp; Sales: stream-delivered prints only (never the poller's sampled latest trade). */
    final Deque<TapePrint> tape = new ArrayDeque<>();
    final java.util.LinkedHashSet<String> tapeKeys = new java.util.LinkedHashSet<>();

    SymbolState(String symbol) {
      this.symbol = symbol;
    }

    /** An immutable copy for readers. */
    public synchronized View view() {
      return new View(symbol, quote, quoteObservedAt, polledAt, lastIex, lastConsolidated, minuteBar, dailyBar,
          Map.copyOf(closes), halted, haltReason, updatedAt, List.copyOf(recentConsolidated));
    }
  }

  /** A consistent snapshot of a symbol's state. */
  public record View(String symbol, Quote quote, Instant quoteObservedAt, Instant polledAt, Trade lastIex,
                     Trade lastConsolidated, Bar minuteBar, Bar dailyBar, Map<LocalDate, BigDecimal> closes,
                     boolean halted, String haltReason, Instant updatedAt, List<Trade> recentConsolidated) {

    public Trade lastTrade() {
      if (lastConsolidated == null) {
        return lastIex;
      }
      if (lastIex == null) {
        return lastConsolidated;
      }
      return lastIex.ts().isAfter(lastConsolidated.ts()) ? lastIex : lastConsolidated;
    }

    public Optional<BigDecimal> close(LocalDate d) {
      return Optional.ofNullable(closes.get(d));
    }

    public boolean empty() {
      return quote == null && lastIex == null && lastConsolidated == null && closes.isEmpty() && dailyBar == null;
    }
  }

  private final Map<String, SymbolState> states = new ConcurrentHashMap<>();
  private final Supplier<List<MarketUpdateListener>> listeners;
  private final TimeSource time;

  public QuoteBook(TimeSource time, Supplier<List<MarketUpdateListener>> listeners) {
    this.time = time;
    this.listeners = listeners;
  }

  public Optional<View> view(String symbol) {
    SymbolState s = states.get(symbol);
    return s == null ? Optional.empty() : Optional.of(s.view());
  }

  public Collection<String> symbols() {
    return List.copyOf(states.keySet());
  }

  private SymbolState state(String symbol) {
    return states.computeIfAbsent(symbol, SymbolState::new);
  }

  // ------------------------------------------------------------------------------------------- updates

  /** A stream message (any provider). Returns true when the symbol changed. */
  public boolean onEvent(MarketEvent e) {
    Instant now = time.now();
    String sym;
    switch (e) {
      case QuoteEvent qe -> {
        sym = qe.quote().symbol();
        applyQuote(qe.quote(), now);
      }
      case TradeEvent te -> {
        sym = te.trade().symbol();
        applyTrade(te.trade(), now);
        recordTape(te.trade(), now);
      }
      case BarEvent be -> {
        sym = be.bar().symbol();
        SymbolState s = state(sym);
        synchronized (s) {
          if (s.minuteBar == null || !be.bar().start().isBefore(s.minuteBar.start())) {
            s.minuteBar = be.bar();
          }
          s.updatedAt = now;
        }
      }
      case StatusEvent se -> {
        sym = se.symbol();
        SymbolState s = state(sym);
        synchronized (s) {
          s.halted = se.halted();
          s.haltReason = se.reason();
          s.updatedAt = now;
        }
        log.info("{} {} ({})", sym, se.halted() ? "HALTED" : "resumed", se.reason());
      }
      case NewsEvent ne -> {
        return false;
      }
    }
    notifyListeners(sym);
    return true;
  }

  void applyQuote(Quote q, Instant observedAt) {
    SymbolState s = state(q.symbol());
    synchronized (s) {
      if (s.quote == null || q.ts() == null || s.quote.ts() == null || !q.ts().isBefore(s.quote.ts())) {
        s.quote = q;
        s.quoteObservedAt = observedAt;
      }
      s.updatedAt = observedAt;
    }
  }

  void applyTrade(Trade t, Instant observedAt) {
    SymbolState s = state(t.symbol());
    synchronized (s) {
      if (t.consolidated()) {
        if (s.lastConsolidated == null || !t.ts().isBefore(s.lastConsolidated.ts())) {
          s.lastConsolidated = t;
        }
        if (t.eligible()) {
          s.recentConsolidated.addLast(t);
          while (s.recentConsolidated.size() > 50) {
            s.recentConsolidated.removeFirst();
          }
        }
      } else if (s.lastIex == null || !t.ts().isBefore(s.lastIex.ts())) {
        s.lastIex = t;
      }
      if (t.eligible()) {
        boolean consolidatedFlowing = s.lastConsolidated != null && s.lastConsolidated.observedAt() != null
            && Duration.between(s.lastConsolidated.observedAt(), observedAt).compareTo(CONSOLIDATED_DEDUP) <= 0;
        if ((t.consolidated() || !consolidatedFlowing) && s.bufferedKeys.add(printKey(t))) {
          s.prints.addLast(new Print(t.price(), t.size(), t.ts(), t.observedAt() != null ? t.observedAt()
              : observedAt, t.consolidated()));
          while (s.prints.size() > PRINT_BUFFER) {
            s.prints.removeFirst();
          }
          while (s.bufferedKeys.size() > PRINT_BUFFER) {
            s.bufferedKeys.removeFirst();
          }
        }
      }
      s.updatedAt = observedAt;
    }
  }

  /**
   * Appends a stream-delivered print to the symbol's Time &amp; Sales tape (§15.10), colored against the quote in
   * force now. While consolidated prints flow, IEX prints are skipped (they are in the SIP tape already); a print seen
   * twice (same identity as {@link #printKey}) is kept once.
   */
  void recordTape(Trade t, Instant observedAt) {
    if (t == null || t.price() == null || t.ts() == null) {
      return;
    }
    SymbolState s = state(t.symbol());
    synchronized (s) {
      boolean consolidatedFlowing = s.lastConsolidated != null && s.lastConsolidated.observedAt() != null
          && Duration.between(s.lastConsolidated.observedAt(), observedAt).compareTo(CONSOLIDATED_DEDUP) <= 0;
      if (!t.consolidated() && consolidatedFlowing) {
        return;
      }
      String key = printKey(t);
      if (!s.tapeKeys.add(key)) {
        return;
      }
      Quote q = s.quote;
      s.tape.addLast(new TapePrint(tapeId(t), t.price(), t.size(), t.ts(), t.venue(), t.consolidated(),
          TapeClassifier.classify(t.price(), q == null ? null : q.bid(), q == null ? null : q.ask())));
      while (s.tape.size() > TAPE_SIZE) {
        s.tape.removeFirst();
      }
      while (s.tapeKeys.size() > TAPE_SIZE) {
        s.tapeKeys.removeFirst();
      }
    }
  }

  /** An opaque, compact print id: the provider's trade id when it sends one, else time, price and size. */
  static String tapeId(Trade t) {
    String kind = t.consolidated() ? "c" : "i";
    if (t.id() != null && !t.id().isBlank()) {
      return kind + t.id();
    }
    return kind + Long.toString(t.ts().getEpochSecond(), 36) + "." + t.ts().getNano() + "."
        + t.price().stripTrailingZeros().toPlainString() + "x" + t.size();
  }

  /** The newest {@code limit} tape prints of a symbol, newest first (by trade time, then arrival). */
  public List<TapePrint> tape(String symbol, int limit) {
    SymbolState s = states.get(symbol);
    if (s == null || limit <= 0) {
      return List.of();
    }
    List<TapePrint> out;
    synchronized (s) {
      out = new ArrayList<>(s.tape);
    }
    java.util.Collections.reverse(out);                 // newest arrival first
    out.sort(java.util.Comparator.comparing(TapePrint::ts).reversed());   // stable: arrival order breaks ties
    return out.size() > limit ? List.copyOf(out.subList(0, limit)) : List.copyOf(out);
  }

  /**
   * The identity of a print: the trade id where the provider sends one, else time, price and size. Every snapshot poll
   * (2 s for ORDER/VIEWING symbols) repeats the same {@code latestTrade}, which the stream may also have delivered;
   * buffering it again would let one upstream print fill a resting limit several times over (§12.5).
   */
  static String printKey(Trade t) {
    String kind = t.consolidated() ? "C" : "I";
    return t.id() != null && !t.id().isBlank() ? kind + "|" + t.id() + "|" + t.ts()
        : kind + "|" + t.ts() + "|" + t.price().stripTrailingZeros().toPlainString() + "|" + t.size();
  }

  /**
   * A REST snapshot completed at {@code completedAt} (poller or refresher): it confirms the symbol's quote as of that
   * instant (§12.3, polled symbols) and carries the day bars, from which regular-session closes are learned.
   */
  public void applySnapshot(Snapshot snap, Instant completedAt, LocalDate currentDay, Instant currentDayRthClose) {
    SymbolState s = state(snap.symbol());
    synchronized (s) {
      if (snap.quote() != null) {
        if (s.quote == null || s.quote.ts() == null || snap.quote().ts() == null
            || !snap.quote().ts().isBefore(s.quote.ts())) {
          s.quote = snap.quote();
          s.quoteObservedAt = completedAt;
        }
      }
      s.polledAt = completedAt;
      if (snap.minuteBar() != null && (s.minuteBar == null || !snap.minuteBar().start().isBefore(s.minuteBar.start()))) {
        s.minuteBar = snap.minuteBar();
      }
      if (snap.dailyBar() != null) {
        s.dailyBar = snap.dailyBar();
        LocalDate d = Et.date(snap.dailyBar().start());
        boolean complete = currentDay == null || d.isBefore(currentDay)
            || (d.equals(currentDay) && currentDayRthClose != null && !completedAt.isBefore(currentDayRthClose));
        if (complete) {
          putClose(s, d, snap.dailyBar().close(), false);
        }
      }
      if (snap.prevDailyBar() != null) {
        putClose(s, Et.date(snap.prevDailyBar().start()), snap.prevDailyBar().close(), false);
      }
      s.updatedAt = completedAt;
    }
    if (snap.lastTrade() != null) {
      applyTrade(snap.lastTrade(), completedAt);
    }
    notifyListeners(snap.symbol());
  }

  /** An official close learned elsewhere (daily_mark, SIP daily bars); official values replace snapshot ones. */
  public void putOfficialClose(String symbol, LocalDate date, BigDecimal close) {
    SymbolState s = state(symbol);
    synchronized (s) {
      putClose(s, date, close, true);
    }
  }

  private static void putClose(SymbolState s, LocalDate d, BigDecimal close, boolean official) {
    if (close == null) {
      return;
    }
    if (official || !s.closes.containsKey(d)) {
      s.closes.put(d, close);
    }
    while (s.closes.size() > MAX_CLOSES) {
      s.closes.pollFirstEntry();
    }
  }

  /** Every eligible print observed since the previous drain (engine only, §12.2). */
  public List<Print> drainPrints(String symbol) {
    SymbolState s = states.get(symbol);
    if (s == null) {
      return List.of();
    }
    synchronized (s) {
      List<Print> out = new ArrayList<>(s.prints);
      s.prints.clear();
      s.drainedUpTo = time.now();
      return out;
    }
  }

  public void notifyListeners(String symbol) {
    for (MarketUpdateListener l : listeners.get()) {
      try {
        l.onMarketUpdate(symbol);
      } catch (RuntimeException e) {
        log.warn("MarketUpdateListener {} failed for {}: {}", l.getClass().getSimpleName(), symbol, e.toString());
      }
    }
  }

  /** Symbols with no update for {@code maxAge}, among {@code candidates}. */
  public List<String> staleAmong(Set<String> candidates, Duration maxAge) {
    Instant now = time.now();
    List<String> out = new ArrayList<>();
    for (String sym : candidates) {
      SymbolState s = states.get(sym);
      Instant last = s == null ? null : s.view().updatedAt();
      if (last == null || Duration.between(last, now).compareTo(maxAge) > 0) {
        out.add(sym);
      }
    }
    return out;
  }
}
