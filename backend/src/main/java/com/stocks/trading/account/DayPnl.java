package com.stocks.trading.account;

import com.stocks.trading.order.TradingClock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Day-window helpers for the Account read. Day P&L itself comes from WS4's {@code PerformanceQueries} (the same
 * anchors as the leaderboard, §16.3); the local Day-0 fallback that lived here was removed at the M1 integration.
 */
@Component
public class DayPnl {

  /** Account day P&L and return (fraction, scale 10). */
  public record Account(BigDecimal dayPnl, BigDecimal dayReturn) {}

  private final TradingClock clock;

  public DayPnl(TradingClock clock) {
    this.clock = clock;
  }

  /** The first of the last five trading days up to {@code today} (7 calendar days without a calendar). */
  public LocalDate fiveTradingDaysStart(LocalDate today) {
    try {
      LocalDate d = clock.calendar().tradingDayOnOrBefore(today);
      for (int i = 0; i < 4; i++) {
        d = clock.calendar().previousTradingDay(d);
      }
      return d;
    } catch (RuntimeException e) {
      return today.minusDays(6);
    }
  }

  /** {@code now / then − 1} at scale 10; 0 when {@code then} is unknown or zero. */
  static BigDecimal ratio(BigDecimal now, BigDecimal then) {
    if (then == null || then.signum() <= 0 || now == null) {
      return BigDecimal.ZERO.setScale(10);
    }
    return now.divide(then, 10, RoundingMode.HALF_EVEN).subtract(BigDecimal.ONE);
  }
}
