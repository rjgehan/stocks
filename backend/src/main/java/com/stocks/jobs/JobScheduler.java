package com.stocks.jobs;

import com.stocks.common.domain.SessionType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.PeriodicTask;
import com.stocks.contract.jobs.ScheduledJob;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link ScheduledJob} and {@link PeriodicTask} bean (§25). No workstream uses {@code @Scheduled} or its
 * own scheduler.
 *
 * <ul>
 *   <li><b>Scheduled jobs</b>: the next occurrence comes from {@code occurrencesBetween(cursor, now + 8 days, calendar)},
 *       so early closes and calendar overrides move it ({@link #replan()} after an override). A loop thread checks
 *       about once a second against {@link TimeSource}, so SIM offset clocks and test clock jumps
 *       ({@code POST /api/v1/test/clock} calls {@link #wake()}) are honored. Each due occurrence runs on a virtual
 *       thread through {@link JobRunGuard} ({@code job_run}: RUNNING → DONE or FAILED, never twice). If several
 *       occurrences of one job are due at once (a clock jump), the job's {@link CatchUpPolicy} picks them:
 *       {@code ALL_MISSED} runs all in time order, {@code LATEST_ONLY} and {@code NONE} only the latest. A job never
 *       runs concurrently with itself.</li>
 *   <li><b>Periodic tasks</b>: every {@code interval()} of wall time (not SIM time), never overlapping; a
 *       {@code sessionsOnly()} task ticks only while {@code calendar.stateAt(now)} is PRE, REGULAR or POST.</li>
 *   <li>{@code stocks.jobs.enabled=false} (the {@code test} profile): nothing is armed and {@link #start()} is a no-op;
 *       {@link #runNow} still works, which is how tests run jobs.</li>
 * </ul>
 * {@link StartupGate} calls {@link #start()} after {@link CatchUpRunner} at boot. The registry ({@link #jobs()}) backs
 * {@code /admin/system} and {@code POST /admin/system/jobs/{jobName}/run}.
 */
@Component
public class JobScheduler {

  private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

  /** UPPER_SNAKE, fits {@code job_run.job_name VARCHAR(40)}. */
  public static final Pattern JOB_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
  /** Admin-supplied run keys, e.g. {@code 2026-10-09} or {@code 2026-10-09:RTH_OPEN}. */
  public static final Pattern RUN_KEY = Pattern.compile("^[A-Za-z0-9._:+\\-]{1,48}$");
  static final Duration LOOKAHEAD = Duration.ofDays(8);
  static final Duration EMPTY_PLAN_RETRY = Duration.ofHours(1);
  static final Duration ERROR_PLAN_RETRY = Duration.ofMinutes(1);

  public enum Kind { SCHEDULED, PERIODIC }

  /** One registry row. {@code last*} come from {@code job_run} for scheduled jobs and from memory for tasks. */
  public record JobInfo(String name, Kind kind, CatchUpPolicy catchUp, Duration interval, Instant nextAt,
                        String nextRunKey, String lastRunKey, String lastStatus, Instant lastStartedAt,
                        Instant lastFinishedAt, String lastDetail) {}

  private final class JobState {
    final ScheduledJob job;
    final AtomicBoolean running = new AtomicBoolean();
    volatile Instant cursor;
    volatile JobOccurrence next;
    volatile Instant replanAt;
    volatile String lastPlanError;

    JobState(ScheduledJob job) {
      this.job = job;
    }
  }

  private final class TaskState {
    final PeriodicTask task;
    final AtomicBoolean running = new AtomicBoolean();
    volatile long lastTickNanos;
    volatile Instant lastStartedAt;
    volatile Instant lastFinishedAt;
    volatile String lastStatus;
    volatile String lastDetail;

    TaskState(PeriodicTask task) {
      this.task = task;
    }
  }

  private final List<ScheduledJob> jobs;
  private final Map<String, JobState> jobStates = new LinkedHashMap<>();
  private final Map<String, TaskState> taskStates = new LinkedHashMap<>();
  private final MarketCalendar calendar;
  private final JobRunGuard guard;
  private final TimeSource time;
  private final StocksProperties properties;
  private final LinkedBlockingQueue<Boolean> wakeups = new LinkedBlockingQueue<>();

  private volatile boolean started;
  private Thread loop;
  private ExecutorService executor;

  @Autowired
  public JobScheduler(ObjectProvider<ScheduledJob> jobs, ObjectProvider<PeriodicTask> tasks, MarketCalendar calendar,
                      JobRunGuard guard, TimeSource time, StocksProperties properties) {
    this(jobs.orderedStream().toList(), tasks.orderedStream().toList(), calendar, guard, time, properties);
  }

  JobScheduler(List<ScheduledJob> jobs, List<PeriodicTask> tasks, MarketCalendar calendar, JobRunGuard guard,
               TimeSource time, StocksProperties properties) {
    this.calendar = calendar;
    this.guard = guard;
    this.time = time;
    this.properties = properties;
    Set<String> names = new HashSet<>();
    for (ScheduledJob job : jobs) {
      checkName(job.name(), names, job);
      if (job.catchUp() == null) {
        throw new IllegalStateException("ScheduledJob " + job.name() + " has no CatchUpPolicy");
      }
      jobStates.put(job.name(), new JobState(job));
    }
    for (PeriodicTask task : tasks) {
      checkName(task.name(), names, task);
      if (task.interval() == null || task.interval().isNegative() || task.interval().isZero()) {
        throw new IllegalStateException("PeriodicTask " + task.name() + " needs a positive interval");
      }
      taskStates.put(task.name(), new TaskState(task));
    }
    this.jobs = List.copyOf(jobs);
  }

  private static void checkName(String name, Set<String> names, Object bean) {
    if (name == null || !JOB_NAME.matcher(name).matches()) {
      throw new IllegalStateException("Job name must be UPPER_SNAKE, at most 40 characters: '" + name + "' ("
          + bean.getClass().getName() + ")");
    }
    if (!names.add(name)) {
      throw new IllegalStateException("Duplicate job name '" + name + "' (" + bean.getClass().getName() + ")");
    }
  }

  /** The scheduled jobs in bean order (also {@link CatchUpRunner}'s order for simultaneous occurrences). */
  public List<ScheduledJob> scheduledJobs() {
    return jobs;
  }

  public boolean isEnabled() {
    return properties.jobs().enabled();
  }

  public boolean isStarted() {
    return started;
  }

  // ------------------------------------------------------------------------------------------- lifecycle

  /** Arms every job and task from now on (live occurrences only; the past is {@link CatchUpRunner}'s). */
  public void start() {
    start(time.now());
  }

  /**
   * Arms every job with occurrences strictly after {@code from}. {@link StartupGate} passes the instant the boot
   * catch-up ran up to (inclusive), so no occurrence falls between the two.
   */
  public synchronized void start(Instant from) {
    if (started || !isEnabled()) {
      if (!isEnabled()) {
        log.info("stocks.jobs.enabled=false: {} scheduled job(s) and {} periodic task(s) are not armed",
            jobStates.size(), taskStates.size());
      }
      return;
    }
    long nanos = System.nanoTime();
    jobStates.values().forEach(s -> {
      s.cursor = from;
      s.next = null;
      s.replanAt = null;
    });
    taskStates.values().forEach(s -> s.lastTickNanos = nanos);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    started = true;
    loop = Thread.ofPlatform().name("job-scheduler").daemon(true).start(this::loop);
    log.info("JobScheduler armed {} scheduled job(s) and {} periodic task(s)", jobStates.size(), taskStates.size());
  }

  public synchronized void stop() {
    if (!started) {
      return;
    }
    started = false;
    wake();
    try {
      loop.join(5_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        log.warn("JobScheduler: jobs still running after 10 s at shutdown");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Re-checks due work now (after a clock jump in tests, or new work). */
  public void wake() {
    wakeups.offer(Boolean.TRUE);
  }

  /** Forgets every planned occurrence, e.g. after a calendar override changed today's times. */
  public void replan() {
    jobStates.values().forEach(s -> {
      s.next = null;
      s.replanAt = null;
    });
    wake();
  }

  private void loop() {
    while (started) {
      try {
        tick();
      } catch (RuntimeException e) {
        log.error("JobScheduler tick failed", e);
      }
      try {
        wakeups.poll(1, TimeUnit.SECONDS);
        wakeups.clear();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** One pass: dispatch due job occurrences and periodic ticks. Package-private for tests. */
  void tick() {
    Instant now = time.now();
    for (JobState s : jobStates.values()) {
      if (s.running.get()) {
        continue;
      }
      if (s.cursor == null) {
        s.cursor = now; // first sight: only occurrences after now are live work
      }
      if (s.next == null && (s.replanAt == null || !now.isBefore(s.replanAt))) {
        plan(s, now);
      }
      JobOccurrence next = s.next;
      if (next != null && !next.at().isAfter(now)) {
        dispatch(s, now);
      }
    }
    long nanos = System.nanoTime();
    for (TaskState t : taskStates.values()) {
      if (!t.running.get() && nanos - t.lastTickNanos >= t.task.interval().toNanos()) {
        t.lastTickNanos = nanos;
        if (t.task.sessionsOnly() && !inSession(now)) {
          continue;
        }
        if (t.running.compareAndSet(false, true)) {
          submit(() -> runTask(t));
        }
      }
    }
  }

  private void plan(JobState s, Instant now) {
    try {
      Optional<JobOccurrence> next = sorted(s.job.occurrencesBetween(s.cursor, now.plus(LOOKAHEAD), calendar))
          .stream().filter(o -> o.at().isAfter(s.cursor)).findFirst();
      s.next = next.orElse(null);
      s.replanAt = next.isPresent() ? null : now.plus(EMPTY_PLAN_RETRY);
      s.lastPlanError = null;
    } catch (RuntimeException e) {
      s.next = null;
      s.replanAt = now.plus(ERROR_PLAN_RETRY);
      String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
      if (!msg.equals(s.lastPlanError)) {
        log.warn("JobScheduler: cannot plan {}: {}", s.job.name(), msg);
      }
      s.lastPlanError = msg;
    }
  }

  private void dispatch(JobState s, Instant now) {
    List<JobOccurrence> due;
    try {
      due = pick(sorted(s.job.occurrencesBetween(s.cursor, now, calendar)), s.job.catchUp());
    } catch (RuntimeException e) {
      log.warn("JobScheduler: cannot list due occurrences of {}: {}", s.job.name(), e.toString());
      s.next = null;
      s.replanAt = now.plus(ERROR_PLAN_RETRY);
      return;
    }
    s.cursor = now;
    s.next = null;
    if (due.isEmpty() || !s.running.compareAndSet(false, true)) {
      return;
    }
    submit(() -> {
      try {
        for (JobOccurrence o : due) {
          guard.run(s.job.name(), o.runKey(), () -> s.job.run(o.runKey()));
        }
      } finally {
        s.running.set(false);
      }
    });
  }

  private void submit(Runnable r) {
    ExecutorService ex = executor;
    if (ex == null || ex.isShutdown()) {
      r.run();
    } else {
      ex.execute(r);
    }
  }

  private void runTask(TaskState t) {
    t.lastStartedAt = time.now();
    try {
      t.task.tick();
      t.lastStatus = "DONE";
      t.lastDetail = null;
    } catch (RuntimeException e) {
      t.lastStatus = "FAILED";
      t.lastDetail = e.getClass().getSimpleName() + ": " + e.getMessage();
      log.warn("Periodic task {} failed: {}", t.task.name(), t.lastDetail);
    } finally {
      t.lastFinishedAt = time.now();
      t.running.set(false);
    }
  }

  private boolean inSession(Instant now) {
    try {
      return calendar.stateAt(now).type() != SessionType.CLOSED;
    } catch (RuntimeException e) {
      return false; // no calendar yet (Day-0 stub): sessions-only tasks wait
    }
  }

  static List<JobOccurrence> sorted(List<JobOccurrence> occurrences) {
    if (occurrences == null || occurrences.isEmpty()) {
      return List.of();
    }
    List<JobOccurrence> out = new ArrayList<>(occurrences);
    out.sort(Comparator.comparing(JobOccurrence::at).thenComparing(JobOccurrence::runKey));
    return out;
  }

  /** ALL_MISSED keeps every occurrence; LATEST_ONLY and NONE keep the latest only. Input is sorted. */
  static List<JobOccurrence> pick(List<JobOccurrence> sorted, CatchUpPolicy policy) {
    if (sorted.isEmpty() || policy == CatchUpPolicy.ALL_MISSED) {
      return sorted;
    }
    return List.of(sorted.get(sorted.size() - 1));
  }

  // ------------------------------------------------------------------------------------------- manual runs

  /**
   * Runs one occurrence of a scheduled job now, on the calling thread, through {@link JobRunGuard} (tests and admin
   * "Run now"). Works whether or not jobs are enabled.
   *
   * @param runKeyOrNull null = the latest past occurrence within the catch-up window
   * @throws ApiException {@code NOT_FOUND} for an unknown job or no past occurrence; {@code VALIDATION_FAILED} for a
   *                      malformed run key
   */
  public JobRunGuard.RunResult runNow(String name, String runKeyOrNull) {
    JobState s = jobStates.get(name);
    if (s == null) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "Unknown job '" + name + "'.");
    }
    String runKey = runKeyOrNull;
    if (runKey == null) {
      Instant now = time.now();
      List<JobOccurrence> past = sorted(s.job.occurrencesBetween(now.minus(properties.jobs().catchUpWindow()), now,
          calendar));
      if (past.isEmpty()) {
        throw ApiException.of(ErrorCode.NOT_FOUND, "Job '" + name + "' has no past occurrence to run.");
      }
      runKey = past.get(past.size() - 1).runKey();
    } else {
      validateRunKey(name, runKey);
    }
    String key = runKey;
    return guard.run(name, key, () -> s.job.run(key));
  }

  /**
   * Checks an admin-supplied run key before "Run now" accepts it (the admin endpoint calls this before answering 202):
   * its format, and that it is not an occurrence that has not happened yet, which would run tomorrow's work today
   * (e.g. apply a split before its ex-date).
   *
   * @throws ApiException {@code NOT_FOUND} for an unknown job, {@code VALIDATION_FAILED} for a bad or future key
   */
  public void validateRunKey(String name, String runKey) {
    JobState s = jobStates.get(name);
    if (s == null) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "Unknown job '" + name + "'.");
    }
    if (!RUN_KEY.matcher(runKey).matches()) {
      throw ApiException.validation("runKey", "1-48 characters of letters, digits and . _ : + -");
    }
    if (isFuture(s.job, runKey, time.now())) {
      throw ApiException.validation("runKey", "must be a past occurrence: " + runKey + " has not happened yet");
    }
  }

  /**
   * True when {@code runKey} names an occurrence that has not happened yet: its leading ET date ({@code yyyy-MM-dd},
   * the form every scheduled job's key starts with) is after today, or it is one of the job's upcoming occurrences.
   */
  boolean isFuture(ScheduledJob job, String runKey, Instant now) {
    if (runKey.length() >= 10) {
      try {
        if (LocalDate.parse(runKey.substring(0, 10)).isAfter(Et.date(now))) {
          return true;
        }
      } catch (DateTimeParseException ignored) {
        // not a dated key: only the upcoming occurrences can tell
      }
    }
    try {
      return job.occurrencesBetween(now, now.plus(LOOKAHEAD), calendar).stream()
          .anyMatch(o -> o.runKey().equals(runKey));
    } catch (RuntimeException e) {
      return false;                                // no calendar yet: the date check above is all there is
    }
  }

  /** The registry: every job and task with its next occurrence and last run. */
  public List<JobInfo> jobs() {
    List<JobInfo> out = new ArrayList<>();
    for (JobState s : jobStates.values()) {
      JobOccurrence next = s.next;
      Optional<JobRunGuard.JobRunRecord> last = guard.latest(s.job.name());
      out.add(new JobInfo(s.job.name(), Kind.SCHEDULED, s.job.catchUp(), null, next == null ? null : next.at(),
          next == null ? null : next.runKey(), last.map(JobRunGuard.JobRunRecord::runKey).orElse(null),
          last.map(r -> r.status().name()).orElse(null), last.map(JobRunGuard.JobRunRecord::startedAt).orElse(null),
          last.map(JobRunGuard.JobRunRecord::finishedAt).orElse(null),
          last.map(JobRunGuard.JobRunRecord::detail).orElse(null)));
    }
    for (TaskState t : taskStates.values()) {
      out.add(new JobInfo(t.task.name(), Kind.PERIODIC, null, t.task.interval(), null, null, null, t.lastStatus,
          t.lastStartedAt, t.lastFinishedAt, t.lastDetail));
    }
    return out;
  }
}
