package com.stocks.jobs;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * §25 housekeeping, 02:00 ET every day (trading day or not; {@code run_key} = the ET date, catch-up LATEST_ONLY):
 * expired {@code idempotency_record} rows (§17.6, 7 days), {@code intraday_equity} samples older than
 * {@code stocks.performance.intraday-retention-days} (14) and notifications read more than 90 days ago. Plain
 * deletes outside the engine; each table's writer never revisits such rows.
 */
@Component
public class HousekeepingJob implements ScheduledJob {

  private static final Logger log = LoggerFactory.getLogger(HousekeepingJob.class);

  public static final String NAME = "HOUSEKEEPING";
  static final LocalTime AT = LocalTime.of(2, 0);
  static final Duration READ_NOTIFICATION_RETENTION = Duration.ofDays(90);

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final int intradayRetentionDays;

  public HousekeepingJob(JdbcClient jdbc, TimeSource time, Environment environment) {
    this.jdbc = jdbc;
    this.time = time;
    this.intradayRetentionDays = environment.getProperty("stocks.performance.intraday-retention-days",
        Integer.class, 14);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    List<JobOccurrence> out = new ArrayList<>();
    for (LocalDate d = Et.date(fromExclusive); !d.isAfter(Et.date(toInclusive)); d = d.plusDays(1)) {
      Instant at = Et.at(d, AT);
      if (at.isAfter(fromExclusive) && !at.isAfter(toInclusive)) {
        out.add(new JobOccurrence(at, d.toString()));
      }
    }
    return out;
  }

  @Override
  public void run(String runKey) {
    Instant now = time.now();
    int idem = jdbc.sql("DELETE FROM idempotency_record WHERE expires_at < ?").param(Jdbc.ts(now)).update();
    int samples = jdbc.sql("DELETE FROM intraday_equity WHERE sampled_at < ?")
        .param(Jdbc.ts(now.minus(Duration.ofDays(intradayRetentionDays)))).update();
    int notes = jdbc.sql("DELETE FROM notification WHERE read_at IS NOT NULL AND read_at < ?")
        .param(Jdbc.ts(now.minus(READ_NOTIFICATION_RETENTION))).update();
    log.info("Housekeeping {}: {} idempotency records, {} intraday samples, {} read notifications deleted", runKey,
        idem, samples, notes);
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.LATEST_ONLY;
  }
}
