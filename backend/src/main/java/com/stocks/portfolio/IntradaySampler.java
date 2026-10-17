package com.stocks.portfolio;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.portfolio.config.PerformanceProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code INTRADAY_SAMPLER} (§16.4, §25): every 5 minutes (on the 5-minute grid) from {@code pre_open_at} to
 * {@code post_close_at} on trading days, writes {@code intraday_equity(equity, unit_value, unit_series)} for every
 * ACTIVE account, at the occurrence instant (the 1D curves). Catch-up {@code NONE}: a sample is a picture of "now",
 * so a missed one is never back-filled; a sample run more than one interval late is skipped. Run key = the ISO
 * instant. Retention (14 days) is WS1's 02:00 housekeeping.
 */
@Component
public class IntradaySampler implements ScheduledJob {

  public static final String NAME = "INTRADAY_SAMPLER";

  static final String MERGE = """
      MERGE INTO intraday_equity i
      USING (VALUES (CAST(? AS BIGINT), CAST(? AS TIMESTAMP WITH TIME ZONE), CAST(? AS NUMERIC(19,2)),
                     CAST(? AS NUMERIC(38,18)), CAST(? AS INT)))
        AS v(account_id, sampled_at, equity, unit_value, unit_series)
      ON i.account_id = v.account_id AND i.sampled_at = v.sampled_at
      WHEN MATCHED THEN UPDATE SET equity = v.equity, unit_value = v.unit_value, unit_series = v.unit_series
      WHEN NOT MATCHED THEN INSERT (account_id, sampled_at, equity, unit_value, unit_series)
        VALUES (v.account_id, v.sampled_at, v.equity, v.unit_value, v.unit_series)""";

  /** One {@code intraday_equity} row. */
  public record Sample(long accountId, Instant sampledAt, BigDecimal equity, BigDecimal unitValue, int unitSeries) {}

  private final ValuationService valuation;
  private final PnlCalendar calendar;
  private final JdbcClient jdbc;
  private final TimeSource time;
  private final Duration interval;

  public IntradaySampler(ValuationService valuation, PnlCalendar calendar, JdbcClient jdbc, TimeSource time,
                         PerformanceProperties properties) {
    this.valuation = valuation;
    this.calendar = calendar;
    this.jdbc = jdbc;
    this.time = time;
    this.interval = properties.intradaySampleInterval();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.NONE;
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    List<JobOccurrence> out = new ArrayList<>();
    long step = interval.toMillis();
    boolean allDay = calendar.everyDay();              // crypto trades around the clock (§29): sample 24 h a day
    for (LocalDate d : calendar.tradingDaysBetween(Et.date(fromExclusive).minusDays(2), Et.date(toInclusive))) {
      Instant start = allDay ? Et.startOfDay(d) : calendar.preOpen(d);
      Instant end = allDay ? Et.startOfDay(d.plusDays(1)).minusMillis(1) : calendar.cutoff(d);
      long first = Math.floorDiv(Math.max(start.toEpochMilli(), fromExclusive.toEpochMilli()), step) * step;
      for (long ms = first; ms <= end.toEpochMilli(); ms += step) {
        Instant at = Instant.ofEpochMilli(ms);
        if (at.isBefore(start) || !at.isAfter(fromExclusive)) {
          continue;
        }
        if (at.isAfter(toInclusive)) {
          break;
        }
        out.add(new JobOccurrence(at, at.toString()));
      }
    }
    return out;
  }

  @Override
  public void run(String runKey) {
    Instant at = Instant.parse(runKey);
    if (time.now().isAfter(at.plus(interval))) {
      return;                                        // too late to be a picture of that instant
    }
    sample(at);
  }

  /** Values every ACTIVE account now and stores it as the sample at {@code at}; returns the rows written. */
  public int sample(Instant at) {
    int n = 0;
    for (Valuation v : valuation.valueAllActive().values()) {
      jdbc.sql(MERGE).params(v.accountId(), Jdbc.ts(at), v.equity(), v.unitValue(), v.unitSeries()).update();
      n++;
    }
    return n;
  }

  /** Samples of one account with {@code fromExclusive < sampled_at ≤ toInclusive}, oldest first. */
  public List<Sample> samples(long accountId, Instant fromExclusive, Instant toInclusive) {
    return jdbc.sql("""
            SELECT account_id, sampled_at, equity, unit_value, unit_series FROM intraday_equity
             WHERE account_id = ? AND sampled_at > ? AND sampled_at <= ? ORDER BY sampled_at""")
        .params(accountId, Jdbc.ts(fromExclusive), Jdbc.ts(toInclusive))
        .query((rs, n) -> new Sample(rs.getLong("account_id"), Jdbc.instant(rs, "sampled_at"),
            rs.getBigDecimal("equity"), rs.getBigDecimal("unit_value"), rs.getInt("unit_series")))
        .list();
  }
}
