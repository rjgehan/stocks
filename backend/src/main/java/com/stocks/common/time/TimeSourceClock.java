package com.stocks.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** A {@link Clock} that reads a {@link TimeSource}, so code that needs a Clock follows test and SIM time. */
final class TimeSourceClock extends Clock {

  private final TimeSource source;
  private final ZoneId zone;

  TimeSourceClock(TimeSource source) {
    this(source, ZoneOffset.UTC);
  }

  private TimeSourceClock(TimeSource source, ZoneId zone) {
    this.source = Objects.requireNonNull(source);
    this.zone = Objects.requireNonNull(zone);
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId newZone) {
    return newZone.equals(zone) ? this : new TimeSourceClock(source, newZone);
  }

  @Override
  public Instant instant() {
    return source.now();
  }

  @Override
  public String toString() {
    return "TimeSourceClock[" + source + "," + zone + "]";
  }
}
