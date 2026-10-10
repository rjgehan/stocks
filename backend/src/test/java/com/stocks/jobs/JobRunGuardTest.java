package com.stocks.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §25 {@code job_run} guard on a fresh database (H2, or embedded PostgreSQL under {@code -Ppg-it}). */
class JobRunGuardTest {

  TestDb db;
  MutableTimeSource time;
  JobRunGuard guard;

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
    guard = new JobRunGuard(db.jdbc(), time);
  }

  @Test
  void anOccurrenceRunsOnceAndIsRecordedDone() {
    AtomicInteger runs = new AtomicInteger();
    JobRunGuard.RunResult first = guard.run("EOD_SNAPSHOT", "2026-10-09", runs::incrementAndGet);
    time.advance(Duration.ofSeconds(5));
    JobRunGuard.RunResult second = guard.run("EOD_SNAPSHOT", "2026-10-09", runs::incrementAndGet);
    assertThat(first.outcome()).isEqualTo(JobRunGuard.Outcome.DONE);
    assertThat(second.outcome()).isEqualTo(JobRunGuard.Outcome.ALREADY_DONE);
    assertThat(second.ran()).isFalse();
    assertThat(runs).hasValue(1);
    JobRunGuard.JobRunRecord row = guard.find("EOD_SNAPSHOT", "2026-10-09").orElseThrow();
    assertThat(row.status()).isEqualTo(JobRunGuard.Status.DONE);
    assertThat(row.startedAt()).isEqualTo(TestTimes.START);
    assertThat(row.finishedAt()).isEqualTo(TestTimes.START);
    assertThat(row.detail()).isNull();
  }

  @Test
  void aFailureIsRecordedAndCanBeRetried() {
    JobRunGuard.RunResult failed = guard.run("ASSET_SYNC", "2026-10-09", () -> {
      throw new IllegalStateException("upstream 503");
    });
    assertThat(failed.outcome()).isEqualTo(JobRunGuard.Outcome.FAILED);
    assertThat(failed.detail()).isEqualTo("IllegalStateException: upstream 503");
    assertThat(guard.find("ASSET_SYNC", "2026-10-09").orElseThrow().status()).isEqualTo(JobRunGuard.Status.FAILED);

    time.advance(Duration.ofMinutes(1));
    JobRunGuard.RunResult retried = guard.run("ASSET_SYNC", "2026-10-09", () -> { });
    assertThat(retried.outcome()).isEqualTo(JobRunGuard.Outcome.DONE);
    JobRunGuard.JobRunRecord row = guard.find("ASSET_SYNC", "2026-10-09").orElseThrow();
    assertThat(row.status()).isEqualTo(JobRunGuard.Status.DONE);
    assertThat(row.startedAt()).isEqualTo(TestTimes.START.plus(Duration.ofMinutes(1)));
    assertThat(row.detail()).isNull();
  }

  @Test
  void aFailureIsAuditedAsSystemJobFailed() {
    guard.setAuditService(new com.stocks.audit.AuditService(db.jdbc(), time,
        com.stocks.config.JacksonConfig.newMapper()));
    guard.run("ASSET_SYNC", "2026-10-09", () -> {
      throw new IllegalStateException("upstream 503");
    });
    guard.run("ASSET_SYNC", "2026-10-10", () -> { });
    assertThat(db.jdbc().sql("SELECT category || '/' || action || '/' || outcome || '/' || target_id FROM audit_log")
        .query(String.class).list()).containsExactly("SYSTEM/JOB_FAILED/ERROR/ASSET_SYNC");
  }

  @Test
  void aRunningRowLeftByACrashedProcessIsTakenOver() {
    db.jdbc().sql("INSERT INTO job_run (job_name, run_key, status, started_at) VALUES (?, ?, 'RUNNING', ?)")
        .params("SESSION_TRANSITION", "2026-10-09:RTH_OPEN", Jdbc.ts(TestTimes.START.minusSeconds(3600))).update();
    AtomicInteger runs = new AtomicInteger();
    JobRunGuard.RunResult r = guard.run("SESSION_TRANSITION", "2026-10-09:RTH_OPEN", runs::incrementAndGet);
    assertThat(r.outcome()).isEqualTo(JobRunGuard.Outcome.DONE);
    assertThat(runs).hasValue(1);
  }

  @Test
  void concurrentRunsOfOneOccurrenceExecuteExactlyOnce() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<JobRunGuard.RunResult>> futures = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      futures.add(pool.submit(() -> {
        start.await();
        return guard.run("CHECK_INVARIANTS", "2026-10-09:14", () -> {
          runs.incrementAndGet();
          sleep(50);
        });
      }));
    }
    start.countDown();
    int ran = 0;
    for (Future<JobRunGuard.RunResult> f : futures) {
      if (f.get().ran()) {
        ran++;
      }
    }
    pool.shutdown();
    assertThat(ran).isEqualTo(1);
    assertThat(runs).hasValue(1);
  }

  @Test
  void latestAndDoneKeys() {
    guard.run("PREV_CLOSE", "2026-10-08", () -> { });
    time.advance(Duration.ofDays(1));
    guard.run("PREV_CLOSE", "2026-10-09", () -> {
      throw new IllegalStateException("x");
    });
    assertThat(guard.latest("PREV_CLOSE").orElseThrow().runKey()).isEqualTo("2026-10-09");
    assertThat(guard.latest("NOPE")).isEmpty();
    assertThat(guard.doneKeys("PREV_CLOSE", List.of("2026-10-07", "2026-10-08", "2026-10-09")))
        .isEqualTo(Set.of("2026-10-08"));
    assertThat(guard.doneKeys("PREV_CLOSE", List.of())).isEmpty();
  }

  @Test
  void namesAndKeysMustFitTheColumns() {
    assertThatThrownBy(() -> guard.run("X".repeat(41), "k", () -> { }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> guard.run("JOB", "k".repeat(49), () -> { }))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
