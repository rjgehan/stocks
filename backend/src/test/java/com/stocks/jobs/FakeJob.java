package com.stocks.jobs;

import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A ScheduledJob with occurrences every {@code period} (aligned to the epoch), recording each run. */
final class FakeJob implements ScheduledJob {

  final String name;
  final Duration period;
  final CatchUpPolicy policy;
  final List<String> runs = new CopyOnWriteArrayList<>();
  volatile RuntimeException failWith;

  FakeJob(String name, Duration period, CatchUpPolicy policy) {
    this.name = name;
    this.period = period;
    this.policy = policy;
  }

  static String key(Instant at) {
    return at.truncatedTo(ChronoUnit.SECONDS).toString();
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    List<JobOccurrence> out = new ArrayList<>();
    long p = period.toSeconds();
    long first = Math.floorDiv(fromExclusive.getEpochSecond(), p) * p + p;
    for (long s = first; s <= toInclusive.getEpochSecond(); s += p) {
      Instant at = Instant.ofEpochSecond(s);
      out.add(new JobOccurrence(at, key(at)));
    }
    return out;
  }

  @Override
  public void run(String runKey) {
    runs.add(runKey);
    RuntimeException f = failWith;
    if (f != null) {
      throw f;
    }
  }

  @Override
  public CatchUpPolicy catchUp() {
    return policy;
  }
}
