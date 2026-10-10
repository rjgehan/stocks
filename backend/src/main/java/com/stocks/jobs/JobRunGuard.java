package com.stocks.jobs;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Text;
import com.stocks.contract.security.RequestMeta;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Exactly-once scheduled work through {@code job_run(job_name, run_key)} (§25): a run claims its row as
 * {@code RUNNING}, then finishes it as {@code DONE} or {@code FAILED}. A {@code DONE} occurrence never runs again.
 * <ul>
 *   <li>{@code FAILED} may be retried (catch-up, admin "Run now"): the retry re-claims the row.</li>
 *   <li>{@code RUNNING} rows that this process is not running were left by a crashed process (the app is single
 *       instance, §12.9) and are taken over; a run in progress here is never started twice.</li>
 * </ul>
 * Claims are conditional statements (an INSERT on the primary key, or an UPDATE guarded by the old status), so two
 * threads racing for the same occurrence cannot both win. The work itself runs outside any transaction; jobs manage
 * their own. Timestamps come from {@link TimeSource}.
 */
@Component
public class JobRunGuard {

  private static final Logger log = LoggerFactory.getLogger(JobRunGuard.class);

  public static final int MAX_JOB_NAME = 40;
  public static final int MAX_RUN_KEY = 48;
  static final int MAX_DETAIL = 2000;

  public enum Status { RUNNING, DONE, FAILED }

  public enum Outcome {
    /** Ran and finished. */
    DONE,
    /** Ran and threw; the row is FAILED with the error as detail. */
    FAILED,
    /** Not run: this occurrence is already DONE. */
    ALREADY_DONE,
    /** Not run: this occurrence is running right now in this process. */
    ALREADY_RUNNING
  }

  public record RunResult(String jobName, String runKey, Outcome outcome, String detail) {
    public boolean ran() {
      return outcome == Outcome.DONE || outcome == Outcome.FAILED;
    }
  }

  public record JobRunRecord(String jobName, String runKey, Status status, Instant startedAt, Instant finishedAt,
                             String detail) {}

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final Set<String> runningHere = ConcurrentHashMap.newKeySet();

  public JobRunGuard(JdbcClient jdbc, TimeSource time) {
    this.jdbc = jdbc;
    this.time = time;
  }

  private volatile AuditService audit;

  /** §17.7: a failed occurrence also writes a {@code SYSTEM/JOB_FAILED} audit row (when an audit service exists). */
  @Autowired(required = false)
  public void setAuditService(AuditService audit) {
    this.audit = audit;
  }

  private void auditFailure(String jobName, String runKey, String detail) {
    AuditService a = audit;
    if (a == null) {
      return;
    }
    try {
      a.record(AuditEntry.of(AuditCategory.SYSTEM, "JOB_FAILED", AuditOutcome.ERROR)
          .withMeta(RequestMeta.system(null)).withTarget("job", jobName).withDetail("runKey", runKey)
          .withDetail("error", detail));
    } catch (RuntimeException e) {
      log.warn("Could not audit the failure of {} [{}]: {}", jobName, runKey, e.toString());
    }
  }

  /** Runs {@code work} for {@code (jobName, runKey)} unless that occurrence is already done or running. */
  public RunResult run(String jobName, String runKey, Runnable work) {
    validate(jobName, runKey);
    String key = jobName + "\u0000" + runKey;
    if (!runningHere.add(key)) {
      return new RunResult(jobName, runKey, Outcome.ALREADY_RUNNING, null);
    }
    try {
      Optional<Outcome> refused = claim(jobName, runKey);
      if (refused.isPresent()) {
        return new RunResult(jobName, runKey, refused.get(), null);
      }
      try {
        work.run();
      } catch (RuntimeException | Error e) {
        String detail = Text.clip(e.getClass().getSimpleName() + ": " + e.getMessage(), MAX_DETAIL);
        finish(jobName, runKey, Status.FAILED, detail);
        log.error("Job {} [{}] failed", jobName, runKey, e);
        auditFailure(jobName, runKey, detail);
        return new RunResult(jobName, runKey, Outcome.FAILED, detail);
      }
      finish(jobName, runKey, Status.DONE, null);
      return new RunResult(jobName, runKey, Outcome.DONE, null);
    } finally {
      runningHere.remove(key);
    }
  }

  /** Empty = claimed (the caller runs the work); otherwise why not. */
  private Optional<Outcome> claim(String jobName, String runKey) {
    try {
      jdbc.sql("INSERT INTO job_run (job_name, run_key, status, started_at) VALUES (?, ?, 'RUNNING', ?)")
          .params(jobName, runKey, Jdbc.ts(time.now()))
          .update();
      return Optional.empty();
    } catch (DuplicateKeyException exists) {
      // fall through: the occurrence has a row already
    }
    Status status = find(jobName, runKey).map(JobRunRecord::status).orElse(Status.FAILED);
    if (status == Status.DONE) {
      return Optional.of(Outcome.ALREADY_DONE);
    }
    // FAILED (a retry) or RUNNING not running in this process (left by a crash): re-claim it conditionally.
    int claimed = jdbc.sql("""
            UPDATE job_run SET status = 'RUNNING', started_at = ?, finished_at = NULL, detail = NULL
             WHERE job_name = ? AND run_key = ? AND status = ?""")
        .params(Jdbc.ts(time.now()), jobName, runKey, status.name())
        .update();
    if (claimed == 1) {
      if (status == Status.RUNNING) {
        log.warn("Job {} [{}] was left RUNNING by an earlier process; running it again", jobName, runKey);
      }
      return Optional.empty();
    }
    return Optional.of(find(jobName, runKey).map(JobRunRecord::status).orElse(Status.RUNNING) == Status.DONE
        ? Outcome.ALREADY_DONE : Outcome.ALREADY_RUNNING);
  }

  private void finish(String jobName, String runKey, Status status, String detail) {
    jdbc.sql("UPDATE job_run SET status = ?, finished_at = ?, detail = ? WHERE job_name = ? AND run_key = ?")
        .params(status.name(), Jdbc.ts(time.now()), detail, jobName, runKey)
        .update();
  }

  public Optional<JobRunRecord> find(String jobName, String runKey) {
    return jdbc.sql("""
            SELECT job_name, run_key, status, started_at, finished_at, detail FROM job_run
             WHERE job_name = ? AND run_key = ?""")
        .params(jobName, runKey)
        .query((rs, n) -> new JobRunRecord(rs.getString("job_name"), rs.getString("run_key"),
            Status.valueOf(rs.getString("status")), Jdbc.instant(rs, "started_at"), Jdbc.instant(rs, "finished_at"),
            rs.getString("detail")))
        .optional();
  }

  /** The most recently started run of a job (the {@code /admin/system} registry). */
  public Optional<JobRunRecord> latest(String jobName) {
    return jdbc.sql("""
            SELECT job_name, run_key, status, started_at, finished_at, detail FROM job_run
             WHERE job_name = ? ORDER BY started_at DESC, run_key DESC""")
        .param(jobName)
        .query((rs, n) -> new JobRunRecord(rs.getString("job_name"), rs.getString("run_key"),
            Status.valueOf(rs.getString("status")), Jdbc.instant(rs, "started_at"), Jdbc.instant(rs, "finished_at"),
            rs.getString("detail")))
        .stream().findFirst();
  }

  /** The subset of {@code runKeys} that is DONE for {@code jobName} (catch-up). */
  public Set<String> doneKeys(String jobName, Collection<String> runKeys) {
    if (runKeys.isEmpty()) {
      return Set.of();
    }
    Set<String> out = new HashSet<>();
    List<String> keys = List.copyOf(new HashSet<>(runKeys));
    for (int from = 0; from < keys.size(); from += 500) {
      List<String> chunk = keys.subList(from, Math.min(keys.size(), from + 500));
      out.addAll(jdbc.sql("SELECT run_key FROM job_run WHERE job_name = :job AND status = 'DONE' AND run_key IN (:keys)")
          .param("job", jobName)
          .param("keys", chunk)
          .query(String.class)
          .list());
    }
    return out;
  }

  static void validate(String jobName, String runKey) {
    if (jobName == null || jobName.isEmpty() || jobName.length() > MAX_JOB_NAME) {
      throw new IllegalArgumentException("job name must be 1-" + MAX_JOB_NAME + " characters: " + jobName);
    }
    if (runKey == null || runKey.isEmpty() || runKey.length() > MAX_RUN_KEY) {
      throw new IllegalArgumentException("run key must be 1-" + MAX_RUN_KEY + " characters: " + runKey);
    }
  }
}
