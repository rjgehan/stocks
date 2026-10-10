package com.stocks.devtools;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.AdjustableTimeSource;
import com.stocks.common.time.TimeSource;
import com.stocks.jobs.JobScheduler;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.5 test-only clock control: {@code POST /api/v1/test/clock {"advance": "PT16M"}} → {@code {"now": Ts}}. Moves
 * the {@link AdjustableTimeSource} (the SIM offset clock in e2e, {@code MutableTimeSource} in ITs) forward by an
 * ISO-8601 duration in {@code (0, P7D]}, then wakes {@link JobScheduler}; the engine's 250 ms sweep picks the new
 * time up by itself. Any authenticated player (session + CSRF, or a key). Used by the Playwright helper
 * {@code advanceClock()}.
 *
 * <p>Absent from production by construction ({@code @Profile("test")}); the prod fail-fast refuses to start if it
 * exists ({@code config.ProdSafetyCheck}). {@code @Hidden}: never in {@code docs/openapi.json}.
 */
@Hidden
@Profile("test")
@RestController
public class TestClockController {

  /** Request body. */
  public record AdvanceRequest(String advance) {}

  /** Response body. */
  public record ClockResponse(Instant now) {}

  static final Duration MAX = Duration.ofDays(7);

  private final TimeSource time;
  private final JobScheduler scheduler;

  public TestClockController(TimeSource time, JobScheduler scheduler) {
    this.time = time;
    this.scheduler = scheduler;
  }

  @PostMapping("/api/v1/test/clock")
  public ClockResponse advance(@RequestBody AdvanceRequest body) {
    Duration d;
    try {
      d = Duration.parse(body == null || body.advance() == null ? "" : body.advance().trim());
    } catch (DateTimeParseException e) {
      throw ApiException.validation("advance", "An ISO-8601 duration such as PT16M");
    }
    if (d.isZero() || d.isNegative() || d.compareTo(MAX) > 0) {
      throw ApiException.validation("advance", "Must be > 0 and <= P7D");
    }
    if (!(time instanceof AdjustableTimeSource adjustable)) {
      throw ApiException.of(ErrorCode.NOT_IMPLEMENTED, "The time source (" + time + ") is not adjustable; "
          + "run with SIM clock=offset or clock=fixed.");
    }
    adjustable.advance(d);
    scheduler.wake();
    return new ClockResponse(time.now());
  }
}
