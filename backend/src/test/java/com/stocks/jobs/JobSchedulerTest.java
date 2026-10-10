package com.stocks.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.PeriodicTask;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestProperties;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §25 JobScheduler: live occurrences once each, clock jumps per catch-up policy, runNow, periodic tasks, registry. */
class JobSchedulerTest {

  static final MarketCalendar NO_CALENDAR = NotImplementedStub.of(MarketCalendar.class, "WS2");

  MutableTimeSource time;
  JobRunGuard guard;
  StocksProperties disabled;

  @BeforeEach
  void setUp() {
    time = TestTimes.clock(); // 14:00:00Z
    guard = new JobRunGuard(TestDb.fresh().jdbc(), time);
    disabled = TestProperties.stocks("stocks.jobs.enabled", "false");
  }

  JobScheduler scheduler(List<ScheduledJob> jobs, List<PeriodicTask> tasks, StocksProperties props) {
    return new JobScheduler(jobs, tasks, NO_CALENDAR, guard, time, props);
  }

  @Test
  void aDueOccurrenceRunsOnceThroughTheGuard() {
    FakeJob hourly = new FakeJob("HOURLY", Duration.ofHours(1), CatchUpPolicy.ALL_MISSED);
    JobScheduler s = scheduler(List.of(hourly), List.of(), disabled);
    s.tick(); // arms from 14:00:00 (an occurrence exactly at "now" is the past: catch-up's)
    assertThat(hourly.runs).isEmpty();
    time.advance(Duration.ofMinutes(59));
    s.tick();
    assertThat(hourly.runs).isEmpty();
    time.advance(Duration.ofMinutes(1));
    s.tick();
    s.tick();
    assertThat(hourly.runs).containsExactly("2026-10-09T15:00:00Z");
    assertThat(guard.find("HOURLY", "2026-10-09T15:00:00Z").orElseThrow().status())
        .isEqualTo(JobRunGuard.Status.DONE);
    assertThat(s.jobs()).singleElement().satisfies(info -> {
      assertThat(info.lastRunKey()).isEqualTo("2026-10-09T15:00:00Z");
      assertThat(info.lastStatus()).isEqualTo("DONE");
      assertThat(info.kind()).isEqualTo(JobScheduler.Kind.SCHEDULED);
    });
  }

  @Test
  void aClockJumpRunsMissedOccurrencesPerPolicy() {
    FakeJob all = new FakeJob("ALL_JOB", Duration.ofHours(1), CatchUpPolicy.ALL_MISSED);
    FakeJob latest = new FakeJob("LATEST_JOB", Duration.ofHours(1), CatchUpPolicy.LATEST_ONLY);
    FakeJob none = new FakeJob("NONE_JOB", Duration.ofHours(1), CatchUpPolicy.NONE);
    JobScheduler s = scheduler(List.of(all, latest, none), List.of(), disabled);
    s.tick();
    time.advance(Duration.ofHours(3).plusMinutes(30)); // 17:30: 15:00, 16:00, 17:00 are due
    s.tick();
    assertThat(all.runs).containsExactly("2026-10-09T15:00:00Z", "2026-10-09T16:00:00Z", "2026-10-09T17:00:00Z");
    assertThat(latest.runs).containsExactly("2026-10-09T17:00:00Z");
    assertThat(none.runs).containsExactly("2026-10-09T17:00:00Z");
  }

  @Test
  void runNowRunsTheLatestPastOccurrenceOrAGivenKey() {
    FakeJob daily = new FakeJob("DAILY", Duration.ofDays(1), CatchUpPolicy.LATEST_ONLY);
    JobScheduler s = scheduler(List.of(daily), List.of(), disabled);
    JobRunGuard.RunResult r = s.runNow("DAILY", null);
    assertThat(r.runKey()).isEqualTo("2026-10-09T00:00:00Z");
    assertThat(r.outcome()).isEqualTo(JobRunGuard.Outcome.DONE);
    assertThat(s.runNow("DAILY", null).outcome()).isEqualTo(JobRunGuard.Outcome.ALREADY_DONE);
    assertThat(s.runNow("DAILY", "2026-10-01").outcome()).isEqualTo(JobRunGuard.Outcome.DONE);
    assertThat(daily.runs).containsExactly("2026-10-09T00:00:00Z", "2026-10-01");
    assertThatThrownBy(() -> s.runNow("NOPE", null)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    assertThatThrownBy(() -> s.runNow("DAILY", "bad key!")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  @Test
  void jobNamesAreValidated() {
    FakeJob bad = new FakeJob("lowercase", Duration.ofHours(1), CatchUpPolicy.NONE);
    assertThatThrownBy(() -> scheduler(List.of(bad), List.of(), disabled))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("UPPER_SNAKE");
    FakeJob a = new FakeJob("SAME", Duration.ofHours(1), CatchUpPolicy.NONE);
    FakeJob b = new FakeJob("SAME", Duration.ofHours(2), CatchUpPolicy.NONE);
    assertThatThrownBy(() -> scheduler(List.of(a, b), List.of(), disabled))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
  }

  @Test
  void periodicTasksTickOnWallTimeAndSessionsOnlyTasksWaitForACalendar() {
    AtomicInteger always = new AtomicInteger();
    AtomicInteger sessions = new AtomicInteger();
    JobScheduler s = scheduler(List.of(), List.of(task("ALWAYS", false, always), task("SESSIONS", true, sessions)),
        disabled);
    s.tick();
    assertThat(always).hasValue(1);
    assertThat(sessions).as("calendar is a Day-0 stub → no session").hasValue(0);
    assertThat(s.jobs()).extracting(JobScheduler.JobInfo::name).containsExactly("ALWAYS", "SESSIONS");
  }

  @Test
  void startedSchedulerWakesUpOnAClockJump() throws Exception {
    FakeJob hourly = new FakeJob("WAKE_JOB", Duration.ofHours(1), CatchUpPolicy.ALL_MISSED);
    JobScheduler s = scheduler(List.of(hourly), List.of(), TestProperties.stocks("stocks.jobs.enabled", "true"));
    s.start(time.now());
    try {
      assertThat(s.isStarted()).isTrue();
      time.advance(Duration.ofHours(1));
      s.wake();
      for (int i = 0; i < 250 && hourly.runs.isEmpty(); i++) {
        Thread.sleep(20); // up to 5 s for the scheduler thread
      }
      assertThat(hourly.runs).containsExactly("2026-10-09T15:00:00Z");
    } finally {
      s.stop();
    }
    assertThat(s.isStarted()).isFalse();
  }

  @Test
  void disabledSchedulerDoesNotStart() {
    JobScheduler s = scheduler(List.of(), List.of(), disabled);
    s.start();
    assertThat(s.isStarted()).isFalse();
    assertThat(s.isEnabled()).isFalse();
  }

  static PeriodicTask task(String name, boolean sessionsOnly, AtomicInteger counter) {
    return new PeriodicTask() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Duration interval() {
        return Duration.ofNanos(1);
      }

      @Override
      public boolean sessionsOnly() {
        return sessionsOnly;
      }

      @Override
      public void tick() {
        counter.incrementAndGet();
      }
    };
  }
}
