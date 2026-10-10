package com.stocks.contract.jobs;

import com.stocks.contract.calendar.MarketCalendar;
import java.time.Instant;
import java.util.List;

/**
 * A calendar-driven job (§25): {@code job_run}-guarded, calendar-aware and replayable. WS1's {@code JobScheduler}
 * discovers every bean of this type; no workstream uses {@code @Scheduled}. Disabled with
 * {@code stocks.jobs.enabled=false} (the {@code test} profile).
 */
public interface ScheduledJob {

  /** Stable, UPPER_SNAKE: {@code job_run.job_name} and the admin "Run now" path segment. */
  String name();

  /** The job's ET schedule expressed through the calendar, so early closes and overrides re-plan it. */
  List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal);

  /** Runs one occurrence. MUST be idempotent per {@code runKey}. */
  void run(String runKey);

  CatchUpPolicy catchUp();
}
