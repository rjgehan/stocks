package com.stocks.marketdata.core;

import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.contract.marketdata.DailyMarkService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code DAILY_REMARK} (§15.5, §25): at 09:00 ET on each trading day, PROVISIONAL day-end marks of the previous trading
 * day are re-marked from SIP data ({@code run_key = <previous date>:REMARK}). Re-running WS4's EOD snapshot when a mark
 * changed is WS4's part of the same row of §25.
 */
public class RemarkJob implements ScheduledJob {

  static final LocalTime AT = LocalTime.of(9, 0);
  static final String SUFFIX = ":REMARK";

  private final DailyMarkService marks;
  private final DailyMarkRepository repository;

  public RemarkJob(DailyMarkService marks, DailyMarkRepository repository) {
    this.marks = marks;
    this.repository = repository;
  }

  @Override
  public String name() {
    return "DAILY_REMARK";
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    return EtSchedule.daily(fromExclusive, toInclusive, d -> cal.isTradingDay(d) ? Et.at(d, AT) : null,
        d -> cal.previousTradingDay(d) + SUFFIX);
  }

  @Override
  public void run(String runKey) {
    LocalDate d = LocalDate.parse(runKey.endsWith(SUFFIX) ? runKey.substring(0, runKey.length() - SUFFIX.length())
        : runKey);
    Set<String> provisional = new TreeSet<>(repository.provisional(d));
    if (!provisional.isEmpty()) {
      marks.ensureDayEndMarks(d, provisional);
    }
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.LATEST_ONLY;
  }
}
