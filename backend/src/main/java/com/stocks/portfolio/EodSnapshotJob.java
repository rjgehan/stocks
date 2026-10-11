package com.stocks.portfolio;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.contract.marketdata.DailyMarkService;
import com.stocks.portfolio.PortfolioRepository.AccountRow;
import com.stocks.portfolio.config.PerformanceProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * {@code EOD_SNAPSHOT} (§16.4, §25): at {@code post_close_at + 20 min} (20:20 ET, 17:20 on early closes) on trading
 * days, catch-up {@code ALL_MISSED}, run key = the trade date.
 * <ol>
 *   <li>{@code DailyMarkService.ensureDayEndMarks(D, held ∪ {SPY})} (WS2; skipped while it is the Day-0 stub);</li>
 *   <li>for every run open at the cut-off: {@link EodValuator} → {@code equity_snapshot} + {@code snapshot_holding};</li>
 *   <li>{@code benchmark_daily(SPY, D)}, and the same for every extra benchmark (QQQ, §16.6);</li>
 *   <li>{@link EodSnapshotsWritten}: the league writes {@code leaderboard_snapshot}, NEW_LEADER and backfills the
 *       feed.</li>
 * </ol>
 * Idempotent: {@code job_run('EOD_SNAPSHOT', D)} plus MERGE upserts, so a re-run (09:00 re-mark, admin "Run now")
 * rewrites the same rows.
 */
@Component
public class EodSnapshotJob implements ScheduledJob {

  private static final Logger log = LoggerFactory.getLogger(EodSnapshotJob.class);

  public static final String NAME = "EOD_SNAPSHOT";

  private final PortfolioRepository repository;
  private final EodValuator valuator;
  private final EodSnapshotRepository snapshots;
  private final EodSnapshots anchors;
  private final HoldingsReconstructor holdings;
  private final BenchmarkService benchmark;
  private final DailyMarkService dailyMarks;
  private final PnlCalendar calendar;
  private final ApplicationEventPublisher events;
  private final TimeSource time;
  private final PerformanceProperties properties;

  public EodSnapshotJob(PortfolioRepository repository, EodValuator valuator, EodSnapshotRepository snapshots,
                        EodSnapshots anchors, HoldingsReconstructor holdings, BenchmarkService benchmark,
                        DailyMarkService dailyMarks, PnlCalendar calendar, ApplicationEventPublisher events,
                        TimeSource time, PerformanceProperties properties) {
    this.repository = repository;
    this.valuator = valuator;
    this.snapshots = snapshots;
    this.anchors = anchors;
    this.holdings = holdings;
    this.benchmark = benchmark;
    this.dailyMarks = dailyMarks;
    this.calendar = calendar;
    this.events = events;
    this.time = time;
    this.properties = properties;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.ALL_MISSED;
  }

  /** One occurrence per trading day D, at {@code cutoff(D) + eodDelay}, run key {@code D}. */
  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    List<JobOccurrence> out = new ArrayList<>();
    // a cut-off + delay can fall after midnight ET (AlwaysOpenCalendar's 23:59:59 close), so start two days early
    LocalDate firstExclusive = Et.date(fromExclusive).minusDays(2);
    LocalDate last = Et.date(toInclusive);
    for (LocalDate d : calendar.tradingDaysBetween(firstExclusive, last)) {
      Instant at = calendar.cutoff(d).plus(properties.eodDelay());
      if (at.isAfter(fromExclusive) && !at.isAfter(toInclusive)) {
        out.add(new JobOccurrence(at, d.toString()));
      }
    }
    return out;
  }

  @Override
  public void run(String runKey) {
    runFor(LocalDate.parse(runKey));
  }

  /** Snapshots every run open at D's cut-off; returns how many were written. */
  public int runFor(LocalDate tradeDate) {
    if (!calendar.isTradingDay(tradeDate)) {
      throw new IllegalArgumentException(tradeDate + " is not a trading day");
    }
    Instant cutoff = calendar.cutoff(tradeDate);
    if (time.now().isBefore(cutoff)) {
      throw new IllegalStateException("EOD snapshot for " + tradeDate + " before its cut-off " + cutoff);
    }
    List<AccountRow> accounts = repository.accountsOpenAt(cutoff);
    Set<String> symbols = new TreeSet<>();
    symbols.addAll(benchmark.symbols());
    for (AccountRow a : accounts) {
      symbols.addAll(holdings.holdingsAt(a.id(), cutoff).keySet());
    }
    ensureDayEndMarks(tradeDate, symbols);
    int written = 0;
    for (AccountRow a : accounts) {
      EodSnapshot s = valuator.compute(a, tradeDate).orElse(null);
      if (s != null) {
        snapshots.upsert(s);
        written++;
      }
    }
    for (String symbol : benchmark.symbols()) {
      if (benchmark.recordEod(symbol, tradeDate).isEmpty()) {
        log.warn("EOD {}: no usable {} mark, benchmark_daily not written", tradeDate, symbol);
      }
    }
    anchors.invalidate();
    events.publishEvent(new EodSnapshotsWritten(tradeDate, cutoff));
    log.info("EOD {}: {} snapshot(s) written", tradeDate, written);
    return written;
  }

  private void ensureDayEndMarks(LocalDate tradeDate, Set<String> symbols) {
    try {
      dailyMarks.ensureDayEndMarks(tradeDate, symbols);
    } catch (ApiException e) {
      if (e.code() != ErrorCode.NOT_IMPLEMENTED) {
        throw e;
      }
      log.debug("DailyMarkService is not implemented yet; EOD {} uses fallback marks", tradeDate);
    }
  }
}
