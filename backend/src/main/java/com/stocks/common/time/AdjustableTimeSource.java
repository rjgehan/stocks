package com.stocks.common.time;

import java.time.Duration;

/**
 * A {@link TimeSource} that tests and the e2e clock endpoint (§18.5) can move forward: {@link MutableTimeSource} in
 * integration tests, {@link OffsetTimeSource} for the SIM offset clock.
 */
public interface AdjustableTimeSource extends TimeSource {

  /** Moves "now" forward by {@code d} (must be ≥ 0). */
  void advance(Duration d);
}
