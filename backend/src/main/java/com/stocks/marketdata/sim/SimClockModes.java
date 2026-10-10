package com.stocks.marketdata.sim;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.time.OffsetTimeSource;
import com.stocks.common.time.SystemTimeSource;
import com.stocks.common.time.TimeSource;
import com.stocks.marketdata.config.MarketDataProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SIM clock modes of §15.7 ({@code stocks.marketdata.sim.clock}), which decide the application's
 * {@link TimeSource}:
 * <ul>
 *   <li>{@code wall}: {@link SystemTimeSource} (the production clock);</li>
 *   <li>{@code offset}: wall time plus {@code sim.offset}, or anchored so that "now" equals {@code sim.anchor} at boot
 *       and then advances with the wall clock ({@link OffsetTimeSource}; the e2e profile);</li>
 *   <li>{@code fixed}: a {@link MutableTimeSource} at {@code sim.anchor} (the wall time at boot when unset) that only
 *       moves when told to (the {@code test} profile);</li>
 *   <li>{@code speed}: {@link SpeedTimeSource}, {@code sim.speed} simulated seconds per wall second from the
 *       anchor.</li>
 * </ul>
 * Live profiles ({@code hybrid}, {@code alpaca-sip}) always use the wall clock: live quotes and a shifted clock would
 * contradict each other, so a non-{@code wall} mode is ignored with a warning.
 */
public final class SimClockModes {

  private static final Logger log = LoggerFactory.getLogger(SimClockModes.class);

  private SimClockModes() {}

  public static TimeSource timeSource(MarketDataProperties props) {
    MarketDataProperties.Sim sim = props.sim();
    SystemTimeSource wall = new SystemTimeSource();
    if (!props.simulated()) {
      if (!"wall".equals(sim.clock())) {
        log.warn("stocks.marketdata.sim.clock={} ignored: profile {} uses live data and the wall clock",
            sim.clock(), props.profile());
      }
      return wall;
    }
    Instant anchor = sim.anchor() == null ? null : sim.anchor().truncatedTo(ChronoUnit.MICROS);
    return switch (sim.clock()) {
      case "wall" -> wall;
      case "offset" -> anchor != null ? OffsetTimeSource.anchoredAt(wall, anchor)
          : new OffsetTimeSource(wall, sim.offset());
      case "fixed" -> new MutableTimeSource(anchor != null ? anchor : wall.now().truncatedTo(ChronoUnit.SECONDS));
      case "speed" -> new SpeedTimeSource(wall, anchor != null ? anchor : wall.now(), new BigDecimal(sim.speed()));
      default -> throw new IllegalStateException("stocks.marketdata.sim.clock must be wall, offset, fixed or speed; got '"
          + sim.clock() + "'");
    };
  }
}
