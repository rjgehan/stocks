package com.stocks.calendar;

import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.CalendarOverride;
import com.stocks.contract.calendar.TradingDay;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production {@code MarketCalendar} (§9): {@code calendar_override} over {@code trading_day} (ALPACA rows over
 * BUNDLED rows) over the bundled CSV, with {@link NyseCalendarGenerator} as the last resort outside the bundled range.
 * Everything is held in an immutable in-memory snapshot, so reads never touch the database; {@link #reload()} swaps
 * the snapshot after an override or a provider sync.
 *
 * <p>At first use the bundled rows missing from {@code trading_day} are inserted (§9.1 source 3), except inside the
 * window covered by ALPACA rows: there a missing row means the provider says the market is closed.
 */
public class DbMarketCalendar extends AbstractMarketCalendar {

  private static final Logger log = LoggerFactory.getLogger(DbMarketCalendar.class);
  /** Post-close of a shortened session defaults to rth close + 4 h (like 13:00 → 17:00), capped at the normal one. */
  static final Duration DEFAULT_POST_SESSION = Duration.ofHours(4);

  private record Snapshot(NavigableMap<LocalDate, TradingDay> rows, LocalDate alpacaFrom, LocalDate alpacaTo,
                          Map<LocalDate, CalendarOverride> overrides,
                          Map<LocalDate, Optional<TradingDay>> memo) {

    boolean inAlpacaWindow(LocalDate d) {
      return alpacaFrom != null && !d.isBefore(alpacaFrom) && !d.isAfter(alpacaTo);
    }
  }

  private final CalendarRepository repository;
  private final TimeSource time;
  private final List<TradingDay> bundled;
  private volatile Snapshot snapshot;

  public DbMarketCalendar(CalendarRepository repository, TimeSource time) {
    this(repository, time, BundledCalendarLoader.load());
  }

  DbMarketCalendar(CalendarRepository repository, TimeSource time, List<TradingDay> bundled) {
    this.repository = repository;
    this.time = time;
    this.bundled = List.copyOf(bundled);
  }

  /** Seeds missing bundled rows into {@code trading_day} and loads the snapshot (idempotent). */
  public synchronized void initialize() {
    if (snapshot != null) {
      return;
    }
    try {
      Optional<LocalDate[]> window = repository.alpacaWindow();
      Set<LocalDate> existing = repository.tradingDayDates();
      List<TradingDay> missing = new ArrayList<>();
      for (TradingDay d : bundled) {
        boolean covered = window.isPresent() && !d.tradeDate().isBefore(window.get()[0])
            && !d.tradeDate().isAfter(window.get()[1]);
        if (!existing.contains(d.tradeDate()) && !covered) {
          missing.add(d);
        }
      }
      if (!missing.isEmpty()) {
        repository.insert(missing, time.now());
        log.info("Calendar: inserted {} bundled trading days into trading_day", missing.size());
      }
    } catch (RuntimeException e) {
      log.warn("Calendar: could not seed trading_day from the bundled CSV ({}); using the CSV in memory", e.toString());
    }
    reload();
  }

  /** Rebuilds the snapshot from the database (after an override or a provider sync). */
  public synchronized void reload() {
    NavigableMap<LocalDate, TradingDay> rows = new TreeMap<>();
    bundled.forEach(d -> rows.put(d.tradeDate(), d));
    LocalDate alpacaFrom = null;
    LocalDate alpacaTo = null;
    Map<LocalDate, CalendarOverride> overrides = Map.of();
    try {
      Optional<LocalDate[]> window = repository.alpacaWindow();
      if (window.isPresent()) {
        alpacaFrom = window.get()[0];
        alpacaTo = window.get()[1];
        rows.subMap(alpacaFrom, true, alpacaTo, true).clear();
      }
      for (TradingDay d : repository.loadTradingDays()) {
        rows.put(d.tradeDate(), d);
      }
      overrides = repository.allOverrides().stream()
          .collect(Collectors.toMap(CalendarOverride::date, Function.identity()));
    } catch (RuntimeException e) {
      log.warn("Calendar: could not read trading_day/calendar_override ({}); using the bundled CSV", e.toString());
    }
    snapshot = new Snapshot(Collections.unmodifiableNavigableMap(rows), alpacaFrom, alpacaTo, Map.copyOf(overrides),
        new ConcurrentHashMap<>());
  }

  private Snapshot snapshot() {
    Snapshot s = snapshot;
    if (s == null) {
      initialize();
      s = snapshot;
    }
    return s;
  }

  @Override
  public Optional<TradingDay> day(LocalDate date) {
    Snapshot s = snapshot();
    return s.memo().computeIfAbsent(date, d -> resolve(s, d));
  }

  private static Optional<TradingDay> resolve(Snapshot s, LocalDate date) {
    CalendarOverride o = s.overrides().get(date);
    if (o != null && o.closed()) {
      return Optional.empty();
    }
    TradingDay base = s.rows().get(date);
    if (base == null && !s.inAlpacaWindow(date)
        && (date.isBefore(BundledCalendarLoader.FIRST) || date.isAfter(BundledCalendarLoader.LAST))) {
      base = NyseCalendarGenerator.row(date).map(r -> TradingDays.of(r, TradingDays.GENERATED)).orElse(null);
    }
    if (base == null) {
      return Optional.empty();
    }
    return Optional.of(o == null ? base : applyOverride(base, o));
  }

  /** A shortened session: new RTH close and/or post close; early close when RTH ends before the normal time. */
  static TradingDay applyOverride(TradingDay base, CalendarOverride o) {
    Instant rthClose = o.rthCloseAt() != null ? o.rthCloseAt() : base.rthCloseAt();
    Instant postClose;
    if (o.postCloseAt() != null) {
      postClose = o.postCloseAt();
    } else if (o.rthCloseAt() != null) {
      Instant shortened = rthClose.plus(DEFAULT_POST_SESSION);
      postClose = shortened.isBefore(base.postCloseAt()) ? shortened : base.postCloseAt();
    } else {
      postClose = base.postCloseAt();
    }
    if (postClose.isBefore(rthClose)) {
      postClose = rthClose;
    }
    boolean early = base.earlyClose() || rthClose.isBefore(base.rthCloseAt());
    return new TradingDay(base.tradeDate(), base.preOpenAt(), base.rthOpenAt(), rthClose, postClose,
        base.settlementDate(), early, TradingDays.OVERRIDE);
  }

  /** The un-overridden day (validation of a new override). */
  Optional<TradingDay> baseDay(LocalDate date) {
    Snapshot s = snapshot();
    TradingDay base = s.rows().get(date);
    if (base == null && !s.inAlpacaWindow(date)
        && (date.isBefore(BundledCalendarLoader.FIRST) || date.isAfter(BundledCalendarLoader.LAST))) {
      base = NyseCalendarGenerator.row(date).map(r -> TradingDays.of(r, TradingDays.GENERATED)).orElse(null);
    }
    return Optional.ofNullable(base);
  }

  @Override
  public String toString() {
    Snapshot s = snapshot;
    return "DbMarketCalendar[" + (s == null ? "not loaded" : s.rows().size() + " rows, " + s.overrides().size()
        + " overrides, alpaca " + s.alpacaFrom() + ".." + s.alpacaTo()) + "]";
  }
}
