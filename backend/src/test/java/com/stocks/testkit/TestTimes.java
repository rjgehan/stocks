package com.stocks.testkit;

import com.stocks.common.time.MutableTimeSource;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Canonical test instants (§0 date context): Fri 2026-10-09, during regular trading hours; a trade that day settles
 * Tue 2026-10-13 (Mon 2026-10-12 is Columbus Day: NYSE open, banks closed).
 */
public final class TestTimes {

  /** Fri 2026-10-09 14:00:00Z = 10:00 ET, regular session. */
  public static final Instant START = Instant.parse("2026-10-09T14:00:00Z");
  public static final LocalDate TRADE_DATE = LocalDate.of(2026, 10, 9);
  public static final LocalDate COLUMBUS_DAY = LocalDate.of(2026, 10, 12);
  public static final LocalDate SETTLE_DATE = LocalDate.of(2026, 10, 13);

  private TestTimes() {}

  /** A fresh {@link MutableTimeSource} at {@link #START}. */
  public static MutableTimeSource clock() {
    return new MutableTimeSource(START);
  }
}
