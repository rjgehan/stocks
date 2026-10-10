package com.stocks.marketdata.core;

import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/** {@code SPLIT_GUARD} (§14, §25): the 09:20 ET re-check on trading days ({@code run_key = <date>:SPLITGUARD2}). */
public class SplitGuardJob implements ScheduledJob {

  static final LocalTime AT = LocalTime.of(9, 20);

  private final SplitGuard guard;

  public SplitGuardJob(SplitGuard guard) {
    this.guard = guard;
  }

  @Override
  public String name() {
    return "SPLIT_GUARD";
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    return EtSchedule.daily(fromExclusive, toInclusive, d -> cal.isTradingDay(d) ? Et.at(d, AT) : null,
        d -> d + ":SPLITGUARD2");
  }

  @Override
  public void run(String runKey) {
    guard.run();
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.LATEST_ONLY;
  }
}
