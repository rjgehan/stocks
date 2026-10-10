package com.stocks.portfolio;

import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.TransitionType;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.calendar.SessionTransition;
import com.stocks.contract.calendar.TradingDay;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A small NYSE-like {@link MarketCalendar} for WS4 tests: weekdays minus {@code holidays}; 04:00 pre-open, 09:30–16:00
 * regular, 20:00 post close ET, or 13:00 / 17:00 on {@code earlyCloses}. Settlement is the next trading day.
 */
public final class FakeCalendar implements MarketCalendar {

  /** 2026 NYSE holidays around the test dates (§9.2). */
  public static final Set<LocalDate> HOLIDAYS_2026 = Set.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 7),
      LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 25), LocalDate.of(2025, 12, 25), LocalDate.of(2027, 1, 1));
  public static final Set<LocalDate> EARLY_CLOSES_2026 = Set.of(LocalDate.of(2026, 11, 27),
      LocalDate.of(2026, 12, 24));

  private final Set<LocalDate> holidays;
  private final Set<LocalDate> earlyCloses;

  public FakeCalendar() {
    this(HOLIDAYS_2026, EARLY_CLOSES_2026);
  }

  public FakeCalendar(Set<LocalDate> holidays, Set<LocalDate> earlyCloses) {
    this.holidays = holidays;
    this.earlyCloses = earlyCloses;
  }

  @Override
  public Optional<TradingDay> day(LocalDate date) {
    if (!isTradingDay(date)) {
      return Optional.empty();
    }
    boolean early = earlyCloses.contains(date);
    return Optional.of(new TradingDay(date, Et.at(date, LocalTime.of(4, 0)), Et.at(date, LocalTime.of(9, 30)),
        Et.at(date, early ? LocalTime.of(13, 0) : LocalTime.of(16, 0)),
        Et.at(date, early ? LocalTime.of(17, 0) : LocalTime.of(20, 0)), nextTradingDay(date), early, "fake"));
  }

  @Override
  public boolean isTradingDay(LocalDate date) {
    return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY
        && !holidays.contains(date);
  }

  @Override
  public LocalDate nextTradingDay(LocalDate date) {
    LocalDate d = date.plusDays(1);
    while (!isTradingDay(d)) {
      d = d.plusDays(1);
    }
    return d;
  }

  @Override
  public LocalDate previousTradingDay(LocalDate date) {
    LocalDate d = date.minusDays(1);
    while (!isTradingDay(d)) {
      d = d.minusDays(1);
    }
    return d;
  }

  @Override
  public LocalDate tradingDayOnOrBefore(LocalDate date) {
    return isTradingDay(date) ? date : previousTradingDay(date);
  }

  @Override
  public LocalDate settlementDate(LocalDate tradeDate) {
    return nextTradingDay(tradeDate);
  }

  @Override
  public SessionState stateAt(Instant t) {
    LocalDate d = Et.date(t);
    Optional<TradingDay> day = day(d);
    if (day.isEmpty() || t.isBefore(day.get().preOpenAt()) || !t.isBefore(day.get().postCloseAt())) {
      return new SessionState(SessionType.CLOSED, null, t, t, SessionType.PRE, t);
    }
    TradingDay td = day.get();
    SessionType type = t.isBefore(td.rthOpenAt()) ? SessionType.PRE
        : t.isBefore(td.rthCloseAt()) ? SessionType.REGULAR : SessionType.POST;
    return new SessionState(type, d, td.preOpenAt(), td.postCloseAt(), SessionType.CLOSED, td.postCloseAt());
  }

  @Override
  public SessionTransition nextTransitionAfter(Instant t) {
    LocalDate d = nextTradingDay(Et.date(t));
    return new SessionTransition(d, TransitionType.PRE_OPEN, day(d).orElseThrow().preOpenAt());
  }

  @Override
  public List<SessionTransition> transitionsBetween(Instant fromExclusive, Instant toInclusive) {
    return List.of();
  }

  @Override
  public LocalDate tradeDateFor(Instant executionTime) {
    return Et.date(executionTime);
  }

  @Override
  public LocalDate currentPnlDay(Instant t) {
    LocalDate d = Et.date(t);
    Optional<TradingDay> day = day(d);
    if (day.isPresent() && !day.get().preOpenAt().isAfter(t)) {
      return d;
    }
    return previousTradingDay(d);
  }
}
