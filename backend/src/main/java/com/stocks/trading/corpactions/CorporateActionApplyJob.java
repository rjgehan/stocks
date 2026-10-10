package com.stocks.trading.corpactions;

import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.trading.engine.EngineCalls;
import com.stocks.trading.engine.EngineCommand;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code CORPORATE_ACTION_APPLY} (§14, §25): at 03:45 ET on every trading day, one {@code ApplyCorporateActionCmd} per
 * PENDING action with {@code ex_date ≤ date}, in the §14 order (NAME_CHANGE, then splits, then dividends, then the
 * rest), each in its own engine transaction. Run key = the date; catch-up ALL_MISSED replays missed days in order.
 * A failed action does not stop the others; the run then fails (so "Run now" or the next boot retries it).
 */
@Component
public class CorporateActionApplyJob implements ScheduledJob {

  public static final String NAME = "CORPORATE_ACTION_APPLY";
  static final LocalTime AT = LocalTime.of(3, 45);

  private static final Logger log = LoggerFactory.getLogger(CorporateActionApplyJob.class);

  private final CorporateActionStore store;
  private final EngineCalls engine;
  private final TimeSource time;
  private final MarketCalendar calendar;

  public CorporateActionApplyJob(CorporateActionStore store, EngineCalls engine, TimeSource time,
                                 MarketCalendar calendar) {
    this.store = store;
    this.engine = engine;
    this.time = time;
    this.calendar = calendar;
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
      if (at.isAfter(fromExclusive) && !at.isAfter(toInclusive) && cal.isTradingDay(d)) {
        out.add(new JobOccurrence(at, d.toString()));
      }
    }
    return out;
  }

  @Override
  public void run(String runKey) {
    int failed = applyUntil(LocalDate.parse(runKey));
    if (failed > 0) {
      throw new IllegalStateException(failed + " corporate action(s) failed to apply; see the log");
    }
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.ALL_MISSED;
  }

  /**
   * Applies, now, every PENDING action whose ex-date pre-open has already passed (found after its ex-date, or an
   * earlier application failed). Called by the sync job; returns how many failed.
   */
  public int applyOverdue() {
    Instant now = time.now();
    List<CorporateActionRow> due = store.pendingUntil(Et.date(now)).stream()
        .filter(r -> !now.isBefore(ExDates.cutoff(r.exDate(), calendar))).toList();
    return applyAll(due);
  }

  /** Applies every PENDING action with {@code ex_date ≤ date} in the §14 order; returns how many failed. */
  public int applyUntil(LocalDate date) {
    return applyAll(store.pendingUntil(date));
  }

  private int applyAll(List<CorporateActionRow> rows) {
    List<CorporateActionRow> ordered = new ArrayList<>(rows);
    ordered.sort(Comparator.comparing(CorporateActionRow::exDate)
        .thenComparingInt(r -> ExDates.order(r.type())).thenComparingLong(CorporateActionRow::id));
    int failed = 0;
    for (CorporateActionRow r : ordered) {
      try {
        CorporateActionRow after = engine.call(new EngineCommand.ApplyCorporateActionCmd(r.publicId()));
        log.info("Corporate action {} {} {} (ex {}): {}", r.publicId(), r.symbol(), r.type(), r.exDate(),
            after.status());
      } catch (RuntimeException e) {
        failed++;
        log.error("Corporate action {} {} {} failed to apply: {}", r.publicId(), r.symbol(), r.type(), e.toString());
      }
    }
    return failed;
  }
}
