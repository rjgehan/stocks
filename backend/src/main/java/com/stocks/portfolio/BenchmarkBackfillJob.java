package com.stocks.portfolio;

import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.contract.marketdata.BarRequest;
import com.stocks.contract.marketdata.BarSeries;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.portfolio.BenchmarkService.BenchmarkDay;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code BENCHMARK_BACKFILL} (§16.6, §25): once, at boot, when {@code benchmark_daily} lacks the YTD anchor
 * 2025-12-31. Fetches the benchmark's daily bars (raw) from 2025-12-31 to the last trading day before today and
 * writes the chain with B = 1 at 2025-12-31; rows the EOD job wrote later are re-chained onto it. Price return only:
 * historical dividends are not available through {@code MarketDataService} (the EOD job adds
 * {@code daily_mark.dividend_ex} from then on). Fails (and is retried by the next boot's catch-up, or "Run now")
 * when no bars are available.
 */
@Component
public class BenchmarkBackfillJob implements ScheduledJob {

  private static final Logger log = LoggerFactory.getLogger(BenchmarkBackfillJob.class);

  public static final String NAME = "BENCHMARK_BACKFILL";
  public static final LocalDate ANCHOR = LocalDate.of(2025, 12, 31);

  private final BenchmarkService benchmark;
  private final MarketDataService marketData;
  private final PnlCalendar calendar;
  private final TimeSource time;
  private final AtomicBoolean attempted = new AtomicBoolean();

  public BenchmarkBackfillJob(BenchmarkService benchmark, MarketDataService marketData, PnlCalendar calendar,
                              TimeSource time) {
    this.benchmark = benchmark;
    this.marketData = marketData;
    this.calendar = calendar;
    this.time = time;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.ALL_MISSED;
  }

  /** A single occurrence right after {@code fromExclusive} while the anchor row is missing (once per process). */
  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    if (attempted.get() || benchmark.day(ANCHOR).isPresent()) {
      return List.of();
    }
    Instant at = fromExclusive.plusMillis(1);
    return at.isAfter(toInclusive) ? List.of() : List.of(new JobOccurrence(at, ANCHOR.toString()));
  }

  @Override
  public void run(String runKey) {
    attempted.set(true);
    int n = backfill();
    if (n == 0) {
      throw new IllegalStateException("no " + benchmark.symbol() + " daily bars available for the backfill");
    }
  }

  /** Writes the chain from the bars; returns the number of rows written. */
  public int backfill() {
    LocalDate today = Et.date(time.now());
    LocalDate lastDay = calendar.previousTradingDay(today);
    BarSeries series = marketData.bars(new BarRequest(benchmark.symbol(), null, Timeframe.DAY1,
        Et.startOfDay(ANCHOR), calendar.cutoff(lastDay), 1000, AdjustmentMode.RAW, false));
    TreeMap<LocalDate, BigDecimal> closes = new TreeMap<>();
    for (Bar b : series.bars()) {
      LocalDate d = Et.date(b.start());
      if (!d.isBefore(ANCHOR) && !d.isAfter(lastDay) && b.close() != null && b.close().signum() > 0) {
        closes.put(d, b.close());
      }
    }
    if (closes.isEmpty() || !closes.firstKey().equals(ANCHOR)) {
      log.warn("Benchmark backfill: no {} bar for {} ({} bars)", benchmark.symbol(), ANCHOR, closes.size());
      return 0;
    }
    int n = 0;
    for (var e : closes.entrySet()) {
      benchmark.write(e.getKey(), e.getValue(), e.getKey().equals(ANCHOR) ? BigDecimal.ZERO
          : benchmark.dividendEx(e.getKey()));
      n++;
    }
    // re-chain rows the EOD job wrote after the backfilled range onto the new chain
    for (BenchmarkDay later : benchmark.after(closes.lastKey())) {
      benchmark.write(later.tradeDate(), later.mark(), later.dividend());
    }
    log.info("Benchmark backfill: {} {} rows from {} to {}", n, benchmark.symbol(), ANCHOR, closes.lastKey());
    return n;
  }
}
