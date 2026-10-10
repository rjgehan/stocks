package com.stocks.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestProperties;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** §25 catch-up at boot (time order across jobs, per-policy selection, job_run idempotency) and the StartupGate. */
class CatchUpRunnerTest {

  static final MarketCalendar NO_CALENDAR = NotImplementedStub.of(MarketCalendar.class, "WS2");

  MutableTimeSource time = TestTimes.clock(); // Fri 14:00Z
  JobRunGuard guard = new JobRunGuard(TestDb.fresh().jdbc(), time);

  CatchUpRunner runner(List<ScheduledJob> jobs, StocksProperties props) {
    JobScheduler scheduler = new JobScheduler(jobs, List.of(), NO_CALENDAR, guard, time, props);
    return new CatchUpRunner(scheduler, NO_CALENDAR, guard, time, props);
  }

  @Test
  void replaysMissedOccurrencesInTimeOrderAcrossJobs() {
    FakeJob sixHourly = new FakeJob("SIX_HOURLY", Duration.ofHours(6), CatchUpPolicy.ALL_MISSED);
    FakeJob daily = new FakeJob("DAILY", Duration.ofDays(1), CatchUpPolicy.LATEST_ONLY);
    FakeJob sampler = new FakeJob("SAMPLER", Duration.ofMinutes(5), CatchUpPolicy.NONE);
    StocksProperties props = TestProperties.stocks("stocks.jobs.enabled", "true", "stocks.jobs.catch-up-window", "1d");
    CatchUpRunner runner = runner(List.of(sixHourly, daily, sampler), props);
    // already done before the "crash"
    guard.run("SIX_HOURLY", "2026-10-08T18:00:00Z", () -> { });

    List<JobRunGuard.RunResult> results = runner.runAtBoot(time.now());

    // window (2026-10-08T14:00Z, 2026-10-09T14:00Z]: six-hourly 18:00 (done), 00:00, 06:00, 12:00; daily 00:00 only
    assertThat(results).extracting(r -> r.jobName() + "@" + r.runKey()).containsExactly(
        "SIX_HOURLY@2026-10-09T00:00:00Z", "DAILY@2026-10-09T00:00:00Z", "SIX_HOURLY@2026-10-09T06:00:00Z",
        "SIX_HOURLY@2026-10-09T12:00:00Z");
    assertThat(sampler.runs).as("NONE never catches up").isEmpty();
    assertThat(runner.catchUp(time.now())).as("idempotent").isEmpty();
  }

  @Test
  void latestOnlySkipsWhenTheLatestIsDoneAndFailedRunsAreRetried() {
    FakeJob daily = new FakeJob("DAILY", Duration.ofDays(1), CatchUpPolicy.LATEST_ONLY);
    FakeJob flaky = new FakeJob("FLAKY", Duration.ofDays(1), CatchUpPolicy.ALL_MISSED);
    StocksProperties props = TestProperties.stocks("stocks.jobs.enabled", "true", "stocks.jobs.catch-up-window", "2d");
    CatchUpRunner runner = runner(List.of(daily, flaky), props);
    guard.run("DAILY", "2026-10-09T00:00:00Z", () -> { });
    flaky.failWith = new IllegalStateException("boom");
    assertThat(runner.catchUp(time.now())).extracting(JobRunGuard.RunResult::outcome)
        .containsOnly(JobRunGuard.Outcome.FAILED);
    flaky.failWith = null;
    assertThat(runner.catchUp(time.now())).extracting(r -> r.jobName() + "@" + r.outcome())
        .containsExactly("FLAKY@DONE", "FLAKY@DONE");
    assertThat(daily.runs).isEmpty();
  }

  @Test
  void disabledJobsMeanNoCatchUp() {
    FakeJob daily = new FakeJob("DAILY", Duration.ofDays(1), CatchUpPolicy.ALL_MISSED);
    CatchUpRunner runner = runner(List.of(daily), TestProperties.stocks("stocks.jobs.enabled", "false"));
    assertThat(runner.runAtBoot(time.now())).isEmpty();
    assertThat(daily.runs).isEmpty();
  }

  @Test
  void theStartupGateRunsCatchUpThenOpensThenArmsTheScheduler() throws Exception {
    FakeJob daily = new FakeJob("DAILY", Duration.ofDays(1), CatchUpPolicy.ALL_MISSED);
    StocksProperties props = TestProperties.stocks("stocks.jobs.enabled", "true", "stocks.jobs.catch-up-window", "1d");
    JobScheduler scheduler = new JobScheduler(List.of(daily), List.of(), NO_CALENDAR, guard, time, props);
    CatchUpRunner catchUp = new CatchUpRunner(scheduler, NO_CALENDAR, guard, time, props);
    StartupGate gate = new StartupGate(catchUp, scheduler, time, props);
    assertThat(gate.isOpen()).isFalse();
    gate.start();
    try {
      assertThat(daily.runs).containsExactly("2026-10-09T00:00:00Z");
      assertThat(gate.isOpen()).isTrue();
      assertThat(gate.whenOpen().get(1, TimeUnit.SECONDS)).isEqualTo(TestTimes.START);
      assertThat(gate.awaitOpen(Duration.ofSeconds(1))).isEqualTo(Instant.parse("2026-10-09T14:00:00Z"));
      assertThat(scheduler.isStarted()).isTrue();
      assertThat(gate.isRunning()).isTrue();
    } finally {
      gate.stop();
    }
    assertThat(scheduler.isStarted()).isFalse();
  }
}
