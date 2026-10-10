package com.stocks.calendar;

import com.stocks.common.time.Et;
import com.stocks.contract.calendar.TradingDay;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Dev and e2e only ({@code stocks.marketdata.sim.always-open=true}, §9.3): every day is a trading day with REGULAR
 * from 00:00 to 23:59:59 ET, PRE and POST of zero length, and T+1 settlement on the next weekday. It is in-memory
 * only (its zero-length sessions would violate the {@code trading_day} CHECK) and prod refuses to start with it.
 */
public class AlwaysOpenCalendar extends AbstractMarketCalendar {

  static final LocalTime CLOSE = LocalTime.of(23, 59, 59);

  @Override
  public Optional<TradingDay> day(LocalDate date) {
    return Optional.of(new TradingDay(date, Et.startOfDay(date), Et.startOfDay(date), Et.at(date, CLOSE),
        Et.at(date, CLOSE), nextWeekday(date), false, TradingDays.ALWAYS_OPEN));
  }

  private static LocalDate nextWeekday(LocalDate d) {
    LocalDate n = d.plusDays(1);
    while (n.getDayOfWeek() == DayOfWeek.SATURDAY || n.getDayOfWeek() == DayOfWeek.SUNDAY) {
      n = n.plusDays(1);
    }
    return n;
  }

  @Override
  public String toString() {
    return "AlwaysOpenCalendar";
  }
}
