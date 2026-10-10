package com.stocks.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wall-clock time shifted by an offset (the SIM {@code clock=offset} mode, §15.7, §23.2): time keeps advancing with
 * the wall clock, {@link #advance} adds to the offset. {@link #anchoredAt} computes the offset at construction so that
 * "now" starts at the anchor (the e2e profile anchors at 2026-10-09T14:00:00Z).
 */
public final class OffsetTimeSource implements AdjustableTimeSource {

  private final TimeSource base;
  private final AtomicReference<Duration> offset;
  private final Clock clock = new TimeSourceClock(this);

  public OffsetTimeSource(TimeSource base, Duration initialOffset) {
    this.base = Objects.requireNonNull(base, "base");
    this.offset = new AtomicReference<>(Objects.requireNonNull(initialOffset, "initialOffset"));
  }

  /** A source whose "now" equals {@code anchor} at construction and then advances with {@code base}. */
  public static OffsetTimeSource anchoredAt(TimeSource base, Instant anchor) {
    return new OffsetTimeSource(base, Duration.between(base.now(), anchor));
  }

  /** Wall clock plus {@code offset}. */
  public static OffsetTimeSource withOffset(Duration offset) {
    return new OffsetTimeSource(new SystemTimeSource(), offset);
  }

  @Override
  public Instant now() {
    return base.now().plus(offset.get()).truncatedTo(ChronoUnit.MICROS);
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
    offset.updateAndGet(o -> o.plus(d));
  }

  public Duration offset() {
    return offset.get();
  }

  @Override
  public String toString() {
    return "OffsetTimeSource[offset=" + offset.get() + "]";
  }
}
