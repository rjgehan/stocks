package com.stocks.marketdata.sim;

import com.stocks.common.time.AdjustableTimeSource;
import com.stocks.common.time.TimeSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The SIM {@code clock=speed} mode (§15.7): simulated time starts at an anchor and runs {@code speed} times faster than
 * the wall clock ({@code 60} = one simulated minute per wall second). {@link #advance} jumps forward like the other
 * adjustable sources. Instants are truncated to microseconds (§6.4).
 */
public final class SpeedTimeSource implements AdjustableTimeSource {

  private final TimeSource wall;
  private final Instant wallStart;
  private final Instant simStart;
  private final BigDecimal speed;
  private final AtomicReference<Duration> jumped = new AtomicReference<>(Duration.ZERO);
  private final Clock clock = new SourceClock(this, ZoneOffset.UTC);

  public SpeedTimeSource(TimeSource wall, Instant simStart, BigDecimal speed) {
    this.wall = Objects.requireNonNull(wall, "wall");
    this.wallStart = wall.now();
    this.simStart = Objects.requireNonNull(simStart, "simStart");
    if (speed == null || speed.signum() <= 0) {
      throw new IllegalArgumentException("stocks.marketdata.sim.speed must be > 0, got " + speed);
    }
    this.speed = speed;
  }

  @Override
  public Instant now() {
    long wallMicros = ChronoUnit.MICROS.between(wallStart, wall.now());
    long simMicros = new BigDecimal(wallMicros).multiply(speed).setScale(0, RoundingMode.FLOOR).longValueExact();
    return simStart.plus(simMicros, ChronoUnit.MICROS).plus(jumped.get()).truncatedTo(ChronoUnit.MICROS);
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
    jumped.updateAndGet(j -> j.plus(d));
  }

  public BigDecimal speed() {
    return speed;
  }

  @Override
  public String toString() {
    return "SpeedTimeSource[x" + speed.toPlainString() + "]";
  }

  /** A UTC {@link Clock} view of a {@link TimeSource}. */
  static final class SourceClock extends Clock {
    private final TimeSource source;
    private final ZoneId zone;

    SourceClock(TimeSource source, ZoneId zone) {
      this.source = source;
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
      return newZone.equals(zone) ? this : new SourceClock(source, newZone);
    }

    @Override
    public Instant instant() {
      return source.now();
    }
  }
}
