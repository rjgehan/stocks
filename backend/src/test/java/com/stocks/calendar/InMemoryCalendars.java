package com.stocks.calendar;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.calendar.CalendarOverride;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.testkit.TestTimes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Calendars for unit tests without a database: the bundled CSV over an empty repository. */
public final class InMemoryCalendars {

  private InMemoryCalendars() {}

  public static DbMarketCalendar bundled() {
    DbMarketCalendar cal = new DbMarketCalendar(new EmptyRepository(), new MutableTimeSource(TestTimes.START));
    cal.initialize();
    return cal;
  }

  /** A repository with no rows and no overrides; writes are ignored. */
  static final class EmptyRepository extends CalendarRepository {
    EmptyRepository() {
      super(new org.springframework.jdbc.datasource.SimpleDriverDataSource());
    }

    @Override
    public List<TradingDay> loadTradingDays() {
      return List.of();
    }

    @Override
    public Set<LocalDate> tradingDayDates() {
      return Set.of();
    }

    @Override
    public Optional<LocalDate[]> alpacaWindow() {
      return Optional.empty();
    }

    @Override
    public int insert(List<TradingDay> days, Instant now) {
      return days.size();
    }

    @Override
    public List<CalendarOverride> allOverrides() {
      return List.of();
    }
  }
}
