package com.stocks.alerts;

import com.stocks.alerts.AlertRepository.AlertRow;
import com.stocks.alerts.AlertRules.Live;
import com.stocks.common.domain.AlertStatus;
import com.stocks.common.domain.NotificationKind;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.MarketUpdateListener;
import com.stocks.realtime.NotificationService;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Evaluates price alerts against the live market (§19.1), outside the trading engine and with no trading side effects.
 *
 * <ul>
 *   <li>A {@link MarketUpdateListener}: {@link #onMarketUpdate} only marks a symbol that has ACTIVE alerts dirty (a set
 *       lookup; it never blocks, §5.3).</li>
 *   <li>Its own thread ({@code price-alerts}, every {@code stocks.alerts.evaluate-every}) runs {@link #evaluate()}: for
 *       each dirty symbol it reads {@code MarketDataService.quote}, takes the session's live price
 *       ({@link AlertRules#live}; stale, synthetic, delayed and closed-market quotes never count) and fires or re-arms
 *       alerts. Firing is a conditional {@code UPDATE} ({@link AlertRepository#fire}); only the call that changed the
 *       row writes the {@code PRICE_ALERT} notification (keyed {@code ALERT:<alr>:<n>}), which is also pushed on
 *       STOMP and as a Web Push.</li>
 *   <li>It holds one {@code LIST} lease per symbol with an ACTIVE alert (owner {@code alert:<SYM>}), so the symbol keeps
 *       updating while nobody looks at it.</li>
 *   <li>Every {@link #RELOAD_EVERY} the same thread expires alerts, purges old ones and reloads the ACTIVE set from the
 *       database (self-healing), then re-evaluates every symbol once.</li>
 * </ul>
 * The thread is not a job: {@code stocks.jobs.enabled=false} does not stop it; {@code stocks.alerts.enabled=false}
 * does. {@link #evaluate()} is also callable directly (tests), serialized with the thread by a lock.
 */
@Component
public class AlertMonitor implements MarketUpdateListener, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(AlertMonitor.class);

  public static final Duration RELOAD_EVERY = Duration.ofSeconds(60);
  static final String OWNER_PREFIX = "alert:";

  private final AlertRepository alerts;
  private final NotificationService notifications;
  private final MarketDataService marketData;
  private final MarketCalendar calendar;
  private final TimeSource time;
  private final AlertProperties props;

  /** ACTIVE alerts by symbol: written under {@link #lock} only; read by {@link #onMarketUpdate} from any thread. */
  private final Map<String, List<AlertRow>> bySymbol = new ConcurrentHashMap<>();
  private final Set<String> dirty = ConcurrentHashMap.newKeySet();
  private final Set<String> reload = ConcurrentHashMap.newKeySet();
  /** Symbols this monitor holds a lease on (under {@link #lock}). */
  private final Set<String> leased = new HashSet<>();
  private final ReentrantLock lock = new ReentrantLock();
  private volatile long lastFullReloadNanos;
  private volatile boolean loaded;
  private volatile Thread thread;
  private volatile boolean running;

  public AlertMonitor(AlertRepository alerts, NotificationService notifications, MarketDataService marketData,
                      MarketCalendar calendar, TimeSource time, AlertProperties props) {
    this.alerts = alerts;
    this.notifications = notifications;
    this.marketData = marketData;
    this.calendar = calendar;
    this.time = time;
    this.props = props;
  }

  // ------------------------------------------------------------------------------------------- input

  /** {@code QuoteBook} callback: never blocks; ignores symbols without ACTIVE alerts. */
  @Override
  public void onMarketUpdate(String symbol) {
    if (symbol != null && bySymbol.containsKey(symbol)) {
      dirty.add(symbol);
    }
  }

  /** {@link AlertService}: the symbol's alerts changed; reload them and evaluate on the next pass. */
  public void changed(String symbol) {
    if (symbol != null) {
      reload.add(symbol);
      dirty.add(symbol);
    }
  }

  /** Forces a full reload (expire, purge, reload, re-evaluate everything) on the next pass. */
  public void reloadAll() {
    loaded = false;
  }

  // ------------------------------------------------------------------------------------------- evaluation

  /** One pass: reloads what is due, then evaluates every dirty symbol. Returns how many alerts fired. */
  public int evaluate() {
    lock.lock();
    try {
      long nowNanos = System.nanoTime();
      if (!loaded || nowNanos - lastFullReloadNanos >= RELOAD_EVERY.toNanos()) {
        fullReload();
        lastFullReloadNanos = nowNanos;
        loaded = true;
      } else if (!reload.isEmpty()) {
        for (String symbol : List.copyOf(reload)) {
          reload.remove(symbol);
          put(symbol, alerts.active(symbol));
        }
        syncLeases();
      }
      if (dirty.isEmpty()) {
        return 0;
      }
      int fired = 0;
      Instant now = time.now();
      SessionState state = calendar.stateAt(now);
      for (String symbol : List.copyOf(dirty)) {
        dirty.remove(symbol);
        fired += evaluate(symbol, now, state);
      }
      if (!reload.isEmpty()) {                       // a row changed under us: pick it up on the next pass
        for (String symbol : List.copyOf(reload)) {
          reload.remove(symbol);
          put(symbol, alerts.active(symbol));
        }
        syncLeases();
      }
      return fired;
    } finally {
      lock.unlock();
    }
  }

  private void fullReload() {
    Instant now = time.now();
    int expired = alerts.expire(now);
    int purged = alerts.purge(now.minus(props.retention()));
    if (expired > 0 || purged > 0) {
      log.info("Price alerts: {} expired, {} purged", expired, purged);
    }
    reload.clear();
    Map<String, List<AlertRow>> fresh = new TreeMap<>();
    for (AlertRow a : alerts.active()) {
      fresh.computeIfAbsent(a.symbol(), k -> new ArrayList<>()).add(a);
    }
    bySymbol.keySet().retainAll(fresh.keySet());
    bySymbol.putAll(fresh);
    dirty.addAll(fresh.keySet());
    syncLeases();
  }

  private void put(String symbol, List<AlertRow> rows) {
    if (rows.isEmpty()) {
      bySymbol.remove(symbol);
    } else {
      bySymbol.put(symbol, List.copyOf(rows));
    }
  }

  private int evaluate(String symbol, Instant now, SessionState state) {
    List<AlertRow> current = bySymbol.get(symbol);
    if (current == null || current.isEmpty()) {
      return 0;
    }
    Optional<Live> live;
    try {
      live = AlertRules.live(marketData.quote(symbol), state.type(), now, props.maxPriceAge());
    } catch (RuntimeException e) {
      log.debug("quote({}) failed: {}", symbol, e.toString());
      return 0;
    }
    if (live.isEmpty()) {
      return 0;                                      // no live price: nothing fires and nothing re-arms
    }
    LocalDate day = state.tradingDate();
    int fired = 0;
    List<AlertRow> next = new ArrayList<>(current.size());
    for (AlertRow a : current) {
      if (a.expiresAt() != null && !a.expiresAt().isAfter(now)) {
        next.add(a);                                 // becomes EXPIRED on the next full reload; never fires
        continue;
      }
      AlertRow alert = a;
      if (!alert.armed()) {
        boolean newDay = !alert.condition().isPrice() && day != null && alert.lastTriggerDay() != null
            && !day.equals(alert.lastTriggerDay());
        if (!(newDay || AlertRules.rearms(alert.condition(), alert.threshold(), live.get(), props.rearmBand()))) {
          next.add(alert);
          continue;
        }
        if (alerts.rearm(alert.id(), alert.version(), now) != 1) {
          reload.add(symbol);
          next.add(alert);
          continue;
        }
        alert = withArmed(alert);
      }
      if (!AlertRules.holds(alert.condition(), alert.threshold(), live.get()) || coolingDown(alert, now)) {
        next.add(alert);
        continue;
      }
      if (alerts.fire(alert.id(), alert.version(), alert.triggerCount(), alert.repeating(),
          live.get().price().setScale(4, RoundingMode.HALF_EVEN), day, now) != 1) {
        reload.add(symbol);                          // edited, paused or expired meanwhile
        next.add(alert);
        continue;
      }
      fired++;
      notifyFired(alert, live.get());
      if (alert.repeating()) {
        next.add(afterFiring(alert, live.get(), day, now));
      }
    }
    put(symbol, next);
    if (!bySymbol.containsKey(symbol)) {
      syncLeases();
    }
    return fired;
  }

  private boolean coolingDown(AlertRow a, Instant now) {
    return a.repeating() && a.lastTriggeredAt() != null
        && Duration.between(a.lastTriggeredAt(), now).compareTo(props.cooldown()) < 0;
  }

  private void notifyFired(AlertRow a, Live live) {
    int n = a.triggerCount() + 1;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("alertId", a.publicId());
    payload.put("symbol", a.symbol());
    payload.put("condition", a.condition().name());
    payload.put("threshold", AlertService.dto(a).threshold());
    payload.put("price", live.price());
    payload.put("changePct", live.changePct());
    try {
      notifications.notify(a.playerId(), NotificationKind.PRICE_ALERT,
          AlertRules.title(a.symbol(), a.condition(), a.threshold()),
          AlertRules.body(a.symbol(), a.condition(), a.threshold(), live, a.note()), payload,
          "ALERT:" + a.publicId() + ":" + n);
    } catch (RuntimeException e) {
      log.warn("Price alert {} fired but its notification failed: {}", a.publicId(), e.toString());
    }
  }

  private static AlertRow withArmed(AlertRow a) {
    return new AlertRow(a.id(), a.publicId(), a.playerId(), a.symbol(), a.condition(), a.threshold(), a.repeating(),
        a.status(), true, a.note(), a.expiresAt(), a.version(), a.triggerCount(), a.lastTriggeredAt(),
        a.lastTriggerPrice(), a.lastTriggerDay(), a.createdAt(), a.updatedAt());
  }

  private static AlertRow afterFiring(AlertRow a, Live live, LocalDate day, Instant now) {
    return new AlertRow(a.id(), a.publicId(), a.playerId(), a.symbol(), a.condition(), a.threshold(), a.repeating(),
        AlertStatus.ACTIVE, false, a.note(), a.expiresAt(), a.version(), a.triggerCount() + 1, now, live.price(), day,
        a.createdAt(), now);
  }

  // ------------------------------------------------------------------------------------------- leases

  /** Holds a LIST lease on every symbol with an ACTIVE alert and none on the others. */
  private void syncLeases() {
    for (String symbol : bySymbol.keySet()) {
      if (!leased.contains(symbol)) {
        try {
          marketData.lease(symbol, LeasePriority.LIST, owner(symbol));
          leased.add(symbol);
        } catch (RuntimeException e) {
          log.debug("lease({}) failed: {}", symbol, e.toString());
        }
      }
    }
    for (String symbol : List.copyOf(leased)) {
      if (!bySymbol.containsKey(symbol)) {
        try {
          marketData.releaseOwner(owner(symbol));
        } catch (RuntimeException e) {
          log.debug("releaseOwner({}) failed: {}", owner(symbol), e.toString());
        }
        leased.remove(symbol);
      }
    }
  }

  static String owner(String symbol) {
    return OWNER_PREFIX + symbol;
  }

  /** Symbols with ACTIVE alerts (tests). */
  Set<String> watchedSymbols() {
    return Set.copyOf(bySymbol.keySet());
  }

  // ------------------------------------------------------------------------------------------- thread

  @Override
  public void start() {
    if (running || !props.enabled()) {
      return;
    }
    running = true;
    thread = Thread.ofPlatform().daemon().name("price-alerts").start(this::loop);
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
    lock.lock();
    try {
      bySymbol.clear();
      syncLeases();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void loop() {
    long period = Math.max(props.evaluateEvery().toNanos(), TimeUnit.MILLISECONDS.toNanos(50));
    while (running) {
      try {
        evaluate();
      } catch (RuntimeException e) {
        log.warn("Price alert evaluation failed: {}", e.toString());
      }
      LockSupport.parkNanos(period);
    }
  }
}
