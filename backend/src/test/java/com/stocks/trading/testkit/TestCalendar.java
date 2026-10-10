package com.stocks.trading.testkit;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link MarketCalendar} for WS3 tests (WS2 owns the real one): NYSE 2026–2027 from the §9.2 reference table, with
 * 04:00 / 09:30 / 16:00 / 20:00 ET sessions, 13:00 / 17:00 early closes, and T+1 settlement that skips the bank-only
 * holidays (Columbus Day, Veterans Day). Pure and deterministic.
 */
public final class TestCalendar implements MarketCalendar {

  private static final Set<LocalDate> HOLIDAYS = Set.of(
      LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 19), LocalDate.of(2026, 2, 16), LocalDate.of(2026, 4, 3),
      LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 19), LocalDate.of(2026, 7, 3), LocalDate.of(2026, 9, 7),
      LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 25),
      LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 18), LocalDate.of(2027, 2, 15), LocalDate.of(2027, 3, 26),
      LocalDate.of(2027, 5, 31), LocalDate.of(2027, 6, 18), LocalDate.of(2027, 7, 5), LocalDate.of(2027, 9, 6),
      LocalDate.of(2027, 11, 25), LocalDate.of(2027, 12, 24));
  private static final Set<LocalDate> EARLY_CLOSE = Set.of(
      LocalDate.of(2026, 11, 27), LocalDate.of(2026, 12, 24), LocalDate.of(2027, 11, 26));
  private static final Set<LocalDate> BANK_ONLY = Set.of(
      LocalDate.of(2026, 10, 12), LocalDate.of(2026, 11, 11), LocalDate.of(2027, 10, 11), LocalDate.of(2027, 11, 11));

  @Override
  public Optional<TradingDay> day(LocalDate date) {
    if (!isTradingDay(date)) {
      return Optional.empty();
    }
    boolean early = EARLY_CLOSE.contains(date);
    return Optional.of(new TradingDay(date, Et.at(date, LocalTime.of(4, 0)), Et.at(date, LocalTime.of(9, 30)),
        Et.at(date, early ? LocalTime.of(13, 0) : LocalTime.of(16, 0)),
        Et.at(date, early ? LocalTime.of(17, 0) : LocalTime.of(20, 0)), settlementDate(date), early, "test"));
  }

  @Override
  public boolean isTradingDay(LocalDate date) {
    DayOfWeek d = date.getDayOfWeek();
    return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY && !HOLIDAYS.contains(date);
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
    if (!isTradingDay(tradeDate)) {
      throw new IllegalArgumentException(tradeDate + " is not a trading day");
    }
    LocalDate d = nextTradingDay(tradeDate);
    while (BANK_ONLY.contains(d)) {
      d = nextTradingDay(d);
    }
    return d;
  }

  @Override
  public SessionState stateAt(Instant t) {
    LocalDate date = Et.date(t);
    Optional<TradingDay> today = day(date);
    if (today.isPresent()) {
      TradingDay d = today.get();
      if (t.isBefore(d.preOpenAt())) {
        return new SessionState(SessionType.CLOSED, date, Et.startOfDay(date), d.preOpenAt(), SessionType.PRE,
            d.preOpenAt());
      }
      if (t.isBefore(d.rthOpenAt())) {
        return new SessionState(SessionType.PRE, date, d.preOpenAt(), d.rthOpenAt(), SessionType.REGULAR,
            d.rthOpenAt());
      }
      if (t.isBefore(d.rthCloseAt())) {
        return new SessionState(SessionType.REGULAR, date, d.rthOpenAt(), d.rthCloseAt(), SessionType.POST,
            d.rthCloseAt());
      }
      if (t.isBefore(d.postCloseAt())) {
        return new SessionState(SessionType.POST, date, d.rthCloseAt(), d.postCloseAt(), SessionType.CLOSED,
            d.postCloseAt());
      }
      TradingDay next = day(nextTradingDay(date)).orElseThrow();
      return new SessionState(SessionType.CLOSED, date, d.postCloseAt(), next.preOpenAt(), SessionType.PRE,
          next.preOpenAt());
    }
    TradingDay prev = day(previousTradingDay(date)).orElseThrow();
    TradingDay next = day(nextTradingDay(date)).orElseThrow();
    return new SessionState(SessionType.CLOSED, null, prev.postCloseAt(), next.preOpenAt(), SessionType.PRE,
        next.preOpenAt());
  }

  @Override
  public SessionTransition nextTransitionAfter(Instant t) {
    LocalDate d = Et.date(t).minusDays(1);
    for (int i = 0; i < 10; i++, d = d.plusDays(1)) {
      for (SessionTransition s : transitionsOf(d)) {
        if (s.at().isAfter(t)) {
          return s;
        }
      }
    }
    throw new IllegalStateException("no transition after " + t);
  }

  @Override
  public List<SessionTransition> transitionsBetween(Instant fromExclusive, Instant toInclusive) {
    List<SessionTransition> out = new ArrayList<>();
    for (LocalDate d = Et.date(fromExclusive); !d.isAfter(Et.date(toInclusive)); d = d.plusDays(1)) {
      for (SessionTransition s : transitionsOf(d)) {
        if (s.at().isAfter(fromExclusive) && !s.at().isAfter(toInclusive)) {
          out.add(s);
        }
      }
    }
    return out;
  }

  private List<SessionTransition> transitionsOf(LocalDate date) {
    return day(date).map(d -> List.of(new SessionTransition(date, TransitionType.PRE_OPEN, d.preOpenAt()),
        new SessionTransition(date, TransitionType.RTH_OPEN, d.rthOpenAt()),
        new SessionTransition(date, TransitionType.RTH_CLOSE, d.rthCloseAt()),
        new SessionTransition(date, TransitionType.POST_CLOSE, d.postCloseAt()))).orElse(List.of());
  }

  @Override
  public LocalDate tradeDateFor(Instant executionTime) {
    return Et.date(executionTime);
  }

  @Override
  public LocalDate currentPnlDay(Instant t) {
    LocalDate d = tradingDayOnOrBefore(Et.date(t));
    while (day(d).orElseThrow().preOpenAt().isAfter(t)) {
      d = previousTradingDay(d);
    }
    return d;
  }
}
