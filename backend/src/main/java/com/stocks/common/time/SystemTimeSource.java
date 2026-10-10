package com.stocks.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Wall-clock time in UTC, ticking in whole microseconds (see {@link TimeSource}). The production default. */
public final class SystemTimeSource implements TimeSource {

  private final Clock clock = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));

  @Override
  public Instant now() {
    return clock.instant();
  }

  @Override
  public Clock clock() {
    return clock;
  }

  @Override
  public String toString() {
    return "SystemTimeSource";
  }
}
