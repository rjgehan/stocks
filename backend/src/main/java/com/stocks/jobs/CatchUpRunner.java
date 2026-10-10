package com.stocks.jobs;

import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Replays scheduled work missed while the app was down (§25, §9.4), at boot and before {@link StartupGate} opens the
 * engine queue. For each job it lists {@code occurrencesBetween(now − window, now)} (window
 * {@code stocks.jobs.catch-up-window}, 7 days), drops the occurrences already {@code DONE} in {@code job_run}, and
 * applies the job's policy: {@code ALL_MISSED} keeps every missing one, {@code LATEST_ONLY} keeps the most recent
 * occurrence if it is missing, {@code NONE} keeps nothing. The survivors of every job are merged <b>in time order</b>
 * (ties: job bean order) and run one by one through {@link JobRunGuard}, so a FAILED occurrence is retried and a
 * crash mid-catch-up resumes where it stopped. Orders never fill for time the app was down: catch-up only runs job
 * work (expiries, pauses, EOD, syncs).
 *
 * <p>Disabled with {@code stocks.jobs.enabled=false} (the {@code test} profile); tests call {@link #catchUp(Instant)}.
 */
@Component
public class CatchUpRunner {

  private static final Logger log = LoggerFactory.getLogger(CatchUpRunner.class);

  private final JobScheduler scheduler;
  private final MarketCalendar calendar;
  private final JobRunGuard guard;
  private final TimeSource time;
  private final StocksProperties properties;

  public CatchUpRunner(JobScheduler scheduler, MarketCalendar calendar, JobRunGuard guard, TimeSource time,
                       StocksProperties properties) {
    this.scheduler = scheduler;
    this.calendar = calendar;
    this.guard = guard;
    this.time = time;
    this.properties = properties;
  }

  private record Due(JobOccurrence occurrence, int jobIndex, ScheduledJob job) {}

  /** The boot catch-up up to {@code upTo} (inclusive), run by {@link StartupGate}; a no-op when jobs are disabled. */
  public List<JobRunGuard.RunResult> runAtBoot(Instant upTo) {
    if (!properties.jobs().enabled()) {
      return List.of();
    }
    List<JobRunGuard.RunResult> results = catchUp(upTo);
    for (JobRunGuard.RunResult r : results) {
      if (r.outcome() == JobRunGuard.Outcome.FAILED) {
        log.warn("Catch-up: {} [{}] failed: {}", r.jobName(), r.runKey(), r.detail());
      }
    }
    if (!results.isEmpty()) {
      long failed = results.stream().filter(r -> r.outcome() == JobRunGuard.Outcome.FAILED).count();
      log.info("Catch-up ran {} missed occurrence(s), {} failed", results.size(), failed);
    }
    return results;
  }

  /** Replays what is missing in {@code (now − window, now]}; returns one result per occurrence it ran. */
  public List<JobRunGuard.RunResult> catchUp(Instant now) {
    Instant from = now.minus(properties.jobs().catchUpWindow());
    List<Due> due = new ArrayList<>();
    List<ScheduledJob> jobs = scheduler.scheduledJobs();
    for (int i = 0; i < jobs.size(); i++) {
      ScheduledJob job = jobs.get(i);
      if (job.catchUp() == CatchUpPolicy.NONE) {
        continue;
      }
      List<JobOccurrence> occurrences;
      try {
        occurrences = JobScheduler.sorted(job.occurrencesBetween(from, now, calendar));
      } catch (RuntimeException e) {
        log.warn("Catch-up: cannot list occurrences of {}: {}", job.name(), e.toString());
        continue;
      }
      if (occurrences.isEmpty()) {
        continue;
      }
      if (job.catchUp() == CatchUpPolicy.LATEST_ONLY) {
        occurrences = List.of(occurrences.get(occurrences.size() - 1));
      }
      Set<String> done = guard.doneKeys(job.name(), occurrences.stream().map(JobOccurrence::runKey).toList());
      for (JobOccurrence o : occurrences) {
        if (!done.contains(o.runKey())) {
          due.add(new Due(o, i, job));
        }
      }
    }
    due.sort(Comparator.comparing((Due d) -> d.occurrence().at()).thenComparingInt(Due::jobIndex)
        .thenComparing(d -> d.occurrence().runKey()));
    List<JobRunGuard.RunResult> results = new ArrayList<>();
    for (Due d : due) {
      String runKey = d.occurrence().runKey();
      results.add(guard.run(d.job().name(), runKey, () -> d.job().run(runKey)));
    }
    return results;
  }
}
