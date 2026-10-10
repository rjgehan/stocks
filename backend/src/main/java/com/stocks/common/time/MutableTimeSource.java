package com.stocks.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A fixed clock that only moves when told to: the {@code test} profile's time source (SIM {@code clock=fixed},
 * §23.2) and the test kit's default. Thread-safe.
 */
public final class MutableTimeSource implements AdjustableTimeSource {

  private final AtomicReference<Instant> now;
  private final Clock clock = new TimeSourceClock(this);

  public MutableTimeSource(Instant start) {
    this.now = new AtomicReference<>(truncate(Objects.requireNonNull(start, "start")));
  }

  @Override
  public Instant now() {
    return now.get();
  }

  @Override
  public Clock clock() {
    return clock;
  }

  @Override
  public void advance(Duration d) {
    if (d.isNegative()) {
      throw new IllegalArgumentException("Cannot move time backwards: " + d);
    }
    now.updateAndGet(t -> truncate(t.plus(d)));
  }

  /** Jumps to {@code instant}; unlike {@link #advance} this may move backwards (tests only). */
  public void set(Instant instant) {
    now.set(truncate(Objects.requireNonNull(instant)));
  }

  private static Instant truncate(Instant i) {
    return i.truncatedTo(ChronoUnit.MICROS);
  }

  @Override
  public String toString() {
    return "MutableTimeSource[" + now.get() + "]";
  }
}
