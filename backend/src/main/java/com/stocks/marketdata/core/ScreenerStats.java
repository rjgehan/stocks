package com.stocks.marketdata.core;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.PeriodicTask;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.marketdata.spi.CompanyProfile;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Screener statistics (§15.10, {@code screener_stat}), kept current by the {@code SCREENER_STATS} periodic task:
 * <ol>
 *   <li>once per trading day (when the stored window ends before the last trading day before today) the 20-day
 *       average daily volume of every universe symbol (per trading session: a session without a bar counts as
 *       zero), from {@code 1Day} bars in batches of 100 at
 *       {@code BACKGROUND} priority: the SIP basis (display) and, on {@code hybrid}, the IEX basis (the quote's volume
 *       is IEX-only there, so relative volume compares like with like); a failed pass is retried after an hour;</li>
 *   <li>at most {@code profilesPerTick} company profiles per tick (Finnhub {@code profile2}, one call each,
 *       {@code BACKGROUND}) for stocks and ADRs whose profile is missing or older than {@link #PROFILE_TTL}; empty
 *       answers are stored too, failures retried after an hour, a 401/403 turns profiles off for a day.</li>
 * </ol>
 * The SIM is local: both passes run in full on the first tick, or in the background on the first screen
 * ({@link #ensureLocal}). Reads ({@link #stats}) never touch a provider: they return the in-memory copy of the table.
 */
public class ScreenerStats implements PeriodicTask {

  private static final Logger log = LoggerFactory.getLogger(ScreenerStats.class);
  static final int AVG_DAYS = 20;
  static final int WINDOW_SESSIONS = AVG_DAYS + 2;
  static final int BATCH = 100;
  static final Duration PROFILE_TTL = Duration.ofDays(30);
  static final Duration RETRY_AFTER = Duration.ofHours(1);
  static final Duration FORBIDDEN_FOR = Duration.ofHours(24);

  /** One symbol's statistics, as the screener reads them. */
  /**
   * One symbol's statistics, as the screener reads them. {@code volumeAt} and {@code profileAt} are when the averages
   * and the profile were fetched: a split after them changes the share basis (the screener rescales, §15.10).
   */
  public record Stat(Long avgVolume, Long avgVolumeIex, Integer avgVolumeDays, LocalDate avgVolumeAsOf,
                     Instant volumeAt, BigDecimal sharesOutstanding, BigDecimal marketCap, String industry,
                     Instant profileAt) {}

  /** Where the statistics come from, per profile. */
  public record Sources(MarketDataProvider bars, String sipFeedOrNull, boolean iexBasis,
                        MarketDataProvider profilesOrNull, boolean local) {}

  private final ScreenerStatRepository repository;
  private final Sources sources;
  private final Supplier<Collection<String>> universe;
  private final Function<String, AssetType> types;
  private final MarketCalendar calendar;
  private final TimeSource time;
  private final int profilesPerTick;
  private final LongSupplier nanos;
  private volatile Map<String, Stat> snapshot;
  /** {@code nanos} values before which a failed volume pass, or profiles, wait; null when nothing waits. */
  private volatile Long volumesRetryAtNanos;
  private volatile Long profilesOffUntilNanos;
  private final Map<String, Long> profileRetryAtNanos = new ConcurrentHashMap<>();
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean localStarted = new AtomicBoolean();

  public ScreenerStats(ScreenerStatRepository repository, Sources sources, Supplier<Collection<String>> universe,
                       Function<String, AssetType> types, MarketCalendar calendar, TimeSource time,
                       int profilesPerTick) {
    this(repository, sources, universe, types, calendar, time, profilesPerTick, System::nanoTime);
  }

  ScreenerStats(ScreenerStatRepository repository, Sources sources, Supplier<Collection<String>> universe,
                Function<String, AssetType> types, MarketCalendar calendar, TimeSource time, int profilesPerTick,
                LongSupplier nanos) {
    this.repository = repository;
    this.sources = sources;
    this.universe = universe;
    this.types = types;
    this.calendar = calendar;
    this.time = time;
    this.profilesPerTick = Math.max(0, profilesPerTick);
    this.nanos = nanos;
  }

  @Override
  public String name() {
    return "SCREENER_STATS";
  }

  @Override
  public Duration interval() {
    return Duration.ofSeconds(60);
  }

  @Override
  public boolean sessionsOnly() {
    return false;
  }

  @Override
  public void tick() {
    refresh(sources.local() ? Integer.MAX_VALUE : profilesPerTick);
  }

  /**
   * SIM only: starts one full background pass the first time a screen needs statistics and none are stored yet.
   * Returns true while that pass is running.
   */
  public boolean ensureLocal() {
    if (!sources.local()) {
      return false;
    }
    if (localStarted.compareAndSet(false, true) && (stats().isEmpty() || volumesDue())) {
      Thread.ofVirtual().name("screener-stats").start(() -> refresh(Integer.MAX_VALUE));
      return true;                                   // the pass may not have set `running` yet: still warming
    }
    return running.get();
  }

  /** True while a refresh pass runs. */
  public boolean refreshing() {
    return running.get();
  }

  /** The statistics by symbol (loaded from the table once, then kept in memory). */
  public Map<String, Stat> stats() {
    Map<String, Stat> s = snapshot;
    if (s == null) {
      s = reload();
    }
    return s;
  }

  /** One refresh pass: volumes when due, then at most {@code maxProfiles} profiles. Never throws. */
  void refresh(int maxProfiles) {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      if (volumesDue() && passed(volumesRetryAtNanos)) {
        refreshVolumes();
      }
      refreshProfiles(maxProfiles);
    } catch (RuntimeException e) {
      log.warn("Screener statistics refresh failed: {}", e.toString());
    } finally {
      running.set(false);
    }
  }

  /** The last trading day before today (ET): the end of the averaging window. */
  LocalDate targetDay() {
    return calendar.previousTradingDay(Et.date(time.now()));
  }

  /** True when no stored window ends on or after {@link #targetDay}. */
  boolean volumesDue() {
    LocalDate target = targetDay();
    LocalDate newest = null;
    for (Stat s : stats().values()) {
      if (s.avgVolumeAsOf() != null && (newest == null || s.avgVolumeAsOf().isAfter(newest))) {
        newest = s.avgVolumeAsOf();
      }
    }
    return newest == null || newest.isBefore(target);
  }

  void refreshVolumes() {
    LocalDate end = targetDay();
    LocalDate start = end;
    for (int i = 1; i < WINDOW_SESSIONS; i++) {
      start = calendar.previousTradingDay(start);
    }
    List<String> symbols = new ArrayList<>(new TreeSet<>(universe.get()));
    List<LocalDate> sessions = new ArrayList<>();
    for (LocalDate d = end; sessions.size() < AVG_DAYS; d = calendar.previousTradingDay(d)) {
      sessions.addFirst(d);
    }
    Map<String, long[]> sip;
    Map<String, long[]> iex = Map.of();
    try {
      Map<String, LocalDate> firstSip = new HashMap<>();
      sip = averages(symbols, start, end, sources.sipFeedOrNull(), sessions, Map.of(), firstSip);
      if (sources.iexBasis()) {
        iex = averages(symbols, start, end, "iex", sessions, firstSip, new HashMap<>());
      }
    } catch (ProviderException | UnsupportedOperationException e) {
      volumesRetryAtNanos = nanos.getAsLong() + RETRY_AFTER.toNanos();
      log.warn("Screener statistics: daily volumes unavailable ({}); retrying in {}", e.getMessage(), RETRY_AFTER);
      return;
    }
    Map<String, ScreenerStatRepository.Volumes> out = new LinkedHashMap<>();
    for (String s : symbols) {
      long[] a = sip.get(s);
      long[] b = iex.get(s);
      if (a == null && b == null) {
        continue;
      }
      LocalDate asOf = end;
      out.put(s, new ScreenerStatRepository.Volumes(a == null ? null : a[0], b == null ? null : b[0],
          (int) (a != null ? a[1] : b[1]), asOf));
    }
    if (out.isEmpty()) {
      volumesRetryAtNanos = nanos.getAsLong() + RETRY_AFTER.toNanos();
      log.warn("Screener statistics: no daily bars for {}..{}; retrying in {}", start, end, RETRY_AFTER);
      return;
    }
    repository.upsertVolumes(out, time.now());
    reload();
    log.info("Screener statistics: {}-day volume averages for {} symbols (window {}..{})", AVG_DAYS, out.size(), start,
        end);
  }

  /**
   * {symbol → [average volume, days]} over the {@code sessions} window (the last {@link #AVG_DAYS} trading days up to
   * {@code end}). {@code listedSince} gives an earlier first-bar date per symbol (the SIP bars, for the IEX basis);
   * {@code firstBars} receives each symbol's first bar date of this feed.
   */
  private Map<String, long[]> averages(List<String> symbols, LocalDate start, LocalDate end, String feed,
                                       List<LocalDate> sessions, Map<String, LocalDate> listedSince,
                                       Map<String, LocalDate> firstBars) {
    Map<String, long[]> out = new HashMap<>();
    for (int i = 0; i < symbols.size(); i += BATCH) {
      Set<String> batch = Set.copyOf(symbols.subList(i, Math.min(symbols.size(), i + BATCH)));
      Map<String, List<Bar>> bars = sources.bars().bars(new BarQuery(batch, Timeframe.DAY1, Et.startOfDay(start),
          Et.startOfDay(end.plusDays(1)), AdjustmentMode.SPLIT, false, 10_000, feed), RefreshPriority.BACKGROUND);
      bars.forEach((sym, list) -> {
        list.stream().filter(b -> b.start() != null).map(b -> Et.date(b.start())).min(LocalDate::compareTo)
            .ifPresent(d -> firstBars.put(sym, d));
        average(list, sessions, listedSince.get(sym)).ifPresent(a -> out.put(sym, a));
      });
    }
    return out;
  }

  /**
   * The average daily volume over the window's trading sessions (HALF_EVEN). A session without a bar counts as zero
   * volume (a thin symbol with no IEX trade that day), so the divisor is the number of sessions, not of bars; only the
   * sessions before the symbol's first bar (a recent listing) are left out of it. {@code listedSinceOrNull} is an
   * earlier first-bar date from another feed (the IEX basis uses the SIP bars'). Empty when no session counts.
   */
  static Optional<long[]> average(List<Bar> bars, List<LocalDate> sessions, LocalDate listedSinceOrNull) {
    if (sessions.isEmpty()) {
      return Optional.empty();
    }
    Set<LocalDate> window = Set.copyOf(sessions);
    LocalDate first = listedSinceOrNull;
    long total = 0;
    boolean any = false;
    for (Bar b : bars) {
      if (b.start() == null) {
        continue;
      }
      LocalDate d = Et.date(b.start());
      if (first == null || d.isBefore(first)) {
        first = d;
      }
      if (window.contains(d)) {
        total += Math.max(0, b.volume());
        any = true;
      }
    }
    if (!any) {
      return Optional.empty();
    }
    LocalDate since = first;
    long days = sessions.stream().filter(d -> !d.isBefore(since)).count();
    long avg = BigDecimal.valueOf(total).divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_EVEN)
        .longValueExact();
    return Optional.of(new long[] {avg, days});
  }

  void refreshProfiles(int max) {
    MarketDataProvider p = sources.profilesOrNull();
    if (p == null || max <= 0 || !passed(profilesOffUntilNanos)) {
      return;
    }
    Instant now = time.now();
    Map<String, Stat> current = stats();
    List<String> due = new ArrayList<>();
    for (String s : new TreeSet<>(universe.get())) {
      AssetType t = types.apply(s);
      if (t == AssetType.ETF) {
        continue;
      }
      Stat st = current.get(s);
      if (!passed(profileRetryAtNanos.get(s))) {
        continue;
      }
      if (st == null || st.profileAt() == null || Duration.between(st.profileAt(), now).compareTo(PROFILE_TTL) >= 0) {
        due.add(s);
      }
    }
    // never-fetched symbols first, then the oldest profiles
    due.sort((a, b) -> {
      Instant x = current.get(a) == null ? null : current.get(a).profileAt();
      Instant y = current.get(b) == null ? null : current.get(b).profileAt();
      if (x == null || y == null) {
        return x == null ? (y == null ? a.compareTo(b) : -1) : 1;
      }
      return x.compareTo(y) != 0 ? x.compareTo(y) : a.compareTo(b);
    });
    int done = 0;
    for (String s : due) {
      if (done >= max) {
        break;
      }
      try {
        Optional<CompanyProfile> profile = p.profile(s, RefreshPriority.BACKGROUND);
        ScreenerStatRepository.Profile row = profile
            .map(c -> new ScreenerStatRepository.Profile(c.sharesOutstanding(), c.marketCap(), c.industry(), c.source()))
            .orElse(new ScreenerStatRepository.Profile(null, null, null, p.id()));
        repository.upsertProfile(s, row, time.now());
        profileRetryAtNanos.remove(s);
        done++;
      } catch (ProviderException e) {
        if (e.status() == 401 || e.status() == 403) {
          profilesOffUntilNanos = nanos.getAsLong() + FORBIDDEN_FOR.toNanos();
          log.warn("Screener statistics: {} refused profiles ({}); off for {}", p.id(), e.status(), FORBIDDEN_FOR);
          break;
        }
        if (e.throttled()) {
          break;                                           // the shared budget is low: try again next tick
        }
        profileRetryAtNanos.put(s, nanos.getAsLong() + RETRY_AFTER.toNanos());
        log.debug("Screener statistics: profile of {} failed: {}", s, e.getMessage());
      } catch (UnsupportedOperationException e) {
        profilesOffUntilNanos = nanos.getAsLong() + FORBIDDEN_FOR.toNanos();
        break;
      }
    }
    if (done > 0) {
      reload();
    }
  }

  /** True when nothing waits ({@code null}) or the wait is over (overflow-safe nanos comparison). */
  private boolean passed(Long atNanos) {
    return atNanos == null || nanos.getAsLong() - atNanos >= 0;
  }

  private Map<String, Stat> reload() {
    Map<String, Stat> out = new HashMap<>();
    try {
      for (ScreenerStatRepository.Row r : repository.loadAll()) {
        out.put(r.symbol(), new Stat(r.avgVolume(), r.avgVolumeIex(), r.avgVolumeDays(), r.avgVolumeAsOf(),
            r.volumeAt(), r.sharesOutstanding(), r.marketCap(), r.industry(), r.profileAt()));
      }
    } catch (RuntimeException e) {
      log.warn("Screener statistics: could not read screener_stat: {}", e.toString());
      Map<String, Stat> keep = snapshot;
      return keep == null ? Map.of() : keep;
    }
    Map<String, Stat> copy = Map.copyOf(out);
    snapshot = copy;
    return copy;
  }
}
