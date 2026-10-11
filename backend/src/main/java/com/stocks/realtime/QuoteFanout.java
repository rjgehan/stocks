package com.stocks.realtime;

import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.BarRequest;
import com.stocks.contract.marketdata.BarSeries;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.MarketUpdateListener;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.Tape;
import com.stocks.contract.marketdata.TapePrint;
import com.stocks.contract.marketdata.dto.TimeAndSalesDto;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;

/**
 * Pushes market data to STOMP subscribers (§19): a {@link MarketUpdateListener}, so {@code QuoteBook} calls
 * {@link #onMarketUpdate} for every new quote or print. That call only marks the symbol dirty (it must never block,
 * §5.3); a fan-out thread ({@code stomp-fanout}, every {@link #FLUSH_EVERY}) then sends, per symbol and only while
 * someone subscribes:
 * <ul>
 *   <li>{@code /topic/quote.{SYM}}: {@code QuoteDto}, coalesced to at most one per {@link #QUOTE_INTERVAL} (≤ 4/s);</li>
 *   <li>{@code /topic/tick.{SYM}}: {@code TickDto}, at most one per {@link #TICK_INTERVAL} (≤ 1/s);</li>
 *   <li>{@code /topic/bars.{SYM}.1Min}: the forming minute {@code Bar}, at most one per {@link #BARS_INTERVAL};</li>
 *   <li>{@code /topic/trades.{SYM}} (Phase 2, §15.10): a {@code TimeAndSalesDto} with only the prints that are new
 *       since the previous message (none sent when nothing is new), at most one per {@link #TRADES_INTERVAL}.</li>
 * </ul>
 * Coalescing keeps the <b>latest</b> state: an update that arrives inside the interval is sent when the interval ends.
 * Every payload is built from {@code MarketDataService.quote} at send time, so all channels see one {@code QuoteBook}.
 * The same thread expires bots' TTL leases ({@link SubscriptionTracker#expireBotLeases}). The thread is not a job:
 * {@code stocks.jobs.enabled=false} does not stop it (§25).
 */
@Component
public class QuoteFanout implements MarketUpdateListener, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(QuoteFanout.class);

  public static final Duration QUOTE_INTERVAL = Duration.ofMillis(250);
  public static final Duration TICK_INTERVAL = Duration.ofSeconds(1);
  public static final Duration BARS_INTERVAL = Duration.ofSeconds(1);
  public static final Duration TRADES_INTERVAL = Duration.ofMillis(250);
  /** The newest prints a Time &amp; Sales message may carry (a busy symbol's backlog is not replayed). */
  static final int TRADES_BATCH = 50;
  public static final Duration FLUSH_EVERY = Duration.ofMillis(100);
  private static final Duration LEASE_SWEEP_EVERY = Duration.ofSeconds(5);

  /** Per-symbol send state; {@code *Pending}: an update arrived that this channel has not sent yet. */
  private static final class SymbolState {
    boolean quotePending;
    boolean tickPending;
    boolean barsPending;
    boolean tradesPending;
    long lastQuoteNanos = Long.MIN_VALUE;
    long lastTickNanos = Long.MIN_VALUE;
    long lastBarsNanos = Long.MIN_VALUE;
    long lastTradesNanos = Long.MIN_VALUE;
    /** The newest print already sent on {@code /topic/trades.{SYM}} (trade time, then the ids sent at that time). */
    Instant tradesCursor;
    Set<String> tradesCursorIds = Set.of();
  }

  private final MarketDataService marketData;
  private final SubscriptionTracker tracker;
  private final QuoteMessages messages;
  private final TimeSource time;
  private final ObjectProvider<SimpMessageSendingOperations> messaging;
  private final Map<String, SymbolState> states = new ConcurrentHashMap<>();
  private final Set<String> dirty = ConcurrentHashMap.newKeySet();
  private volatile Thread thread;
  private volatile boolean running;
  private long lastLeaseSweepNanos = System.nanoTime();

  public QuoteFanout(MarketDataService marketData, SubscriptionTracker tracker, QuoteMessages messages,
                     TimeSource time, ObjectProvider<SimpMessageSendingOperations> messaging) {
    this.marketData = marketData;
    this.tracker = tracker;
    this.messages = messages;
    this.time = time;
    this.messaging = messaging;
  }

  // ------------------------------------------------------------------------------------------- input

  /** {@code QuoteBook} callback: never blocks; ignores symbols nobody watches. */
  @Override
  public void onMarketUpdate(String symbol) {
    if (symbol != null && tracker.watched(symbol)) {
      markDirty(symbol);
    }
  }

  /** A new subscriber: send the current state within one flush (still within the per-symbol rate). */
  public void prime(String symbol) {
    if (symbol != null) {
      markDirty(symbol);
    }
  }

  private void markDirty(String symbol) {
    SymbolState s = states.computeIfAbsent(symbol, k -> new SymbolState());
    synchronized (s) {
      s.quotePending = true;
      s.tickPending = true;
      s.barsPending = true;
      s.tradesPending = true;
    }
    dirty.add(symbol);
  }

  // ------------------------------------------------------------------------------------------- flush

  /** Sends whatever is due; returns the number of STOMP messages sent. Called by the fan-out thread and tests. */
  public int flush() {
    return flush(System.nanoTime());
  }

  int flush(long nowNanos) {
    if (dirty.isEmpty()) {
      return 0;
    }
    int sent = 0;
    Instant now = time.now();
    SessionType session = null;
    for (String symbol : new ArrayList<>(dirty)) {
      SymbolState s = states.get(symbol);
      if (s == null || !tracker.watched(symbol)) {
        dirty.remove(symbol);
        states.remove(symbol);
        continue;
      }
      boolean quote;
      boolean tick;
      boolean bars;
      boolean trades;
      synchronized (s) {
        quote = s.quotePending && due(s.lastQuoteNanos, QUOTE_INTERVAL, nowNanos)
            && tracker.subscribers(StompDestinations.quote(symbol)) > 0;
        tick = s.tickPending && due(s.lastTickNanos, TICK_INTERVAL, nowNanos)
            && tracker.subscribers(StompDestinations.tick(symbol)) > 0;
        bars = s.barsPending && due(s.lastBarsNanos, BARS_INTERVAL, nowNanos)
            && tracker.subscribers(StompDestinations.bars(symbol)) > 0;
        trades = s.tradesPending && due(s.lastTradesNanos, TRADES_INTERVAL, nowNanos)
            && tracker.subscribers(StompDestinations.trades(symbol)) > 0;
        if (quote) {
          s.quotePending = false;
          s.lastQuoteNanos = nowNanos;
        }
        if (tick) {
          s.tickPending = false;
          s.lastTickNanos = nowNanos;
        }
        if (bars) {
          s.barsPending = false;
          s.lastBarsNanos = nowNanos;
        }
        if (trades) {
          s.tradesPending = false;
          s.lastTradesNanos = nowNanos;
        }
        // a channel nobody subscribes to has nothing pending
        if (tracker.subscribers(StompDestinations.quote(symbol)) == 0) {
          s.quotePending = false;
        }
        if (tracker.subscribers(StompDestinations.tick(symbol)) == 0) {
          s.tickPending = false;
        }
        if (tracker.subscribers(StompDestinations.bars(symbol)) == 0) {
          s.barsPending = false;
        }
        if (tracker.subscribers(StompDestinations.trades(symbol)) == 0) {
          s.tradesPending = false;
          s.tradesCursor = null;                       // a new subscriber starts from the current tape
          s.tradesCursorIds = Set.of();
        }
        if (!s.quotePending && !s.tickPending && !s.barsPending && !s.tradesPending) {
          dirty.remove(symbol);
        }
      }
      if (!quote && !tick && !bars && !trades) {
        continue;
      }
      try {
        if (quote || tick) {
          QuoteView view = marketData.quote(symbol);
          if (quote) {
            if (session == null) {
              session = messages.session(now);
            }
            send(StompDestinations.quote(symbol), messages.quote(view, now, session));
            sent++;
          }
          if (tick) {
            send(StompDestinations.tick(symbol), messages.tick(view));
            sent++;
          }
        }
        if (bars) {
          Bar bar = formingMinuteBar(symbol, now);
          if (bar != null) {
            send(StompDestinations.bars(symbol), QuoteMessages.bar(bar));
            sent++;
          }
        }
        if (trades && sendNewPrints(symbol, s, now)) {
          sent++;
        }
      } catch (RuntimeException e) {
        log.warn("STOMP fan-out of {} failed: {}", symbol, e.toString());
      }
    }
    return sent;
  }

  /**
   * §15.10: sends the prints newer than the symbol's cursor (by trade time; at the cursor's own time, the ids not sent
   * yet), newest first, at most {@link #TRADES_BATCH}. Returns false when nothing is new (no message).
   */
  private boolean sendNewPrints(String symbol, SymbolState s, Instant now) {
    Tape tape = marketData.timeAndSales(symbol, TRADES_BATCH);
    Instant cursor;
    Set<String> cursorIds;
    synchronized (s) {
      cursor = s.tradesCursor;
      cursorIds = s.tradesCursorIds;
    }
    List<TapePrint> fresh = new ArrayList<>();
    for (TapePrint p : tape.prints()) {
      if (cursor == null || p.ts().isAfter(cursor) || p.ts().equals(cursor) && !cursorIds.contains(p.id())) {
        fresh.add(p);
      }
    }
    if (fresh.isEmpty()) {
      return false;
    }
    Instant newest = fresh.get(0).ts();
    Set<String> ids = new java.util.HashSet<>();
    for (TapePrint p : tape.prints()) {
      if (p.ts().equals(newest)) {
        ids.add(p.id());
      }
    }
    if (cursor != null && newest.equals(cursor)) {
      ids.addAll(cursorIds);
    }
    synchronized (s) {
      s.tradesCursor = newest;
      s.tradesCursorIds = Set.copyOf(ids);
    }
    send(StompDestinations.trades(symbol), TimeAndSalesDto.of(tape, fresh, now));
    return true;
  }

  private static boolean due(long lastNanos, Duration interval, long nowNanos) {
    return lastNanos == Long.MIN_VALUE || nowNanos - lastNanos >= interval.toNanos();
  }

  /** The latest 1Min bar of the last two minutes (the forming bar from {@code BarService}'s live tail, §15.5). */
  private Bar formingMinuteBar(String symbol, Instant now) {
    BarSeries series = marketData.bars(new BarRequest(symbol, null, Timeframe.MIN1, now.minus(Duration.ofMinutes(2)),
        now, 2, AdjustmentMode.SPLIT, true));
    List<Bar> bars = series == null ? List.of() : series.bars();
    return bars.isEmpty() ? null : bars.get(bars.size() - 1);
  }

  private void send(String destination, Object payload) {
    SimpMessageSendingOperations ops = messaging.getIfAvailable();
    if (ops != null) {
      ops.convertAndSend(destination, payload);
    }
  }

  // ------------------------------------------------------------------------------------------- thread

  @Override
  public void start() {
    if (running) {
      return;
    }
    running = true;
    thread = Thread.ofPlatform().daemon().name("stomp-fanout").start(this::loop);
  }

  @Override
  public void stop() {
    running = false;
    Thread t = thread;
    if (t != null) {
      LockSupport.unpark(t);
      try {
        t.join(TimeUnit.SECONDS.toMillis(2));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    thread = null;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void loop() {
    while (running) {
      try {
        flush();
        long now = System.nanoTime();
        if (now - lastLeaseSweepNanos >= LEASE_SWEEP_EVERY.toNanos()) {
          lastLeaseSweepNanos = now;
          tracker.expireBotLeases();
        }
      } catch (RuntimeException e) {
        log.warn("STOMP fan-out loop: {}", e.toString());
      }
      LockSupport.parkNanos(FLUSH_EVERY.toNanos());
    }
  }

  /** Symbols with unsent updates (for tests). */
  Set<String> dirtySymbols() {
    return Set.copyOf(dirty);
  }
}
