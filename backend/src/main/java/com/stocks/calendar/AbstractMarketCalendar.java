package com.stocks.calendar;

import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.TransitionType;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.calendar.SessionTransition;
import com.stocks.contract.calendar.TradingDay;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The session model of §9.3 on top of a single primitive, {@link #day(LocalDate)}: every instant comes from the
 * {@link TradingDay} rows, never from constants, so overrides, early closes, DST and the always-open dev calendar all
 * follow automatically.
 *
 * <pre>
 * CLOSED ──pre_open──► PRE ──rth_open──► REGULAR ──rth_close──► POST ──post_close──► CLOSED
 * </pre>
 * Zero-length sessions (the always-open calendar's PRE and POST) are skipped by {@link #stateAt} but their
 * transitions are still reported, in order, so a transition job sees all four every day.
 */
public abstract class AbstractMarketCalendar implements MarketCalendar {

  /** Searches for the next or previous trading day give up after this many calendar days. */
  static final int MAX_SCAN_DAYS = 400;

  @Override
  public boolean isTradingDay(LocalDate date) {
    return day(date).isPresent();
  }

  @Override
  public LocalDate nextTradingDay(LocalDate date) {
    LocalDate d = date.plusDays(1);
    for (int i = 0; i < MAX_SCAN_DAYS; i++, d = d.plusDays(1)) {
      if (isTradingDay(d)) {
        return d;
      }
    }
    throw new IllegalStateException("No trading day within " + MAX_SCAN_DAYS + " days after " + date);
  }

  @Override
  public LocalDate previousTradingDay(LocalDate date) {
    LocalDate d = date.minusDays(1);
    for (int i = 0; i < MAX_SCAN_DAYS; i++, d = d.minusDays(1)) {
      if (isTradingDay(d)) {
        return d;
      }
    }
    throw new IllegalStateException("No trading day within " + MAX_SCAN_DAYS + " days before " + date);
  }

  @Override
  public LocalDate tradingDayOnOrBefore(LocalDate date) {
    return isTradingDay(date) ? date : previousTradingDay(date);
  }

  @Override
  public LocalDate settlementDate(LocalDate tradeDate) {
    return day(tradeDate).map(TradingDay::settlementDate)
        .orElseThrow(() -> new IllegalArgumentException(tradeDate + " is not a trading day"));
  }

  @Override
  public LocalDate tradeDateFor(Instant executionTime) {
    return Et.date(executionTime);
  }

  @Override
  public LocalDate currentPnlDay(Instant t) {
    LocalDate d = Et.date(t);
    Optional<TradingDay> today = day(d);
    if (today.isPresent() && !t.isBefore(today.get().preOpenAt())) {
      return d;
    }
    return previousTradingDay(d);
  }

  @Override
  public SessionState stateAt(Instant t) {
    LocalDate date = Et.date(t);
    Optional<TradingDay> today = day(date);
    if (today.isPresent()) {
      TradingDay d = today.get();
      SessionType inSession = sessionWithin(d, t);
      if (inSession != null) {
        Instant start = switch (inSession) {
          case PRE -> d.preOpenAt();
          case REGULAR -> d.rthOpenAt();
          default -> d.rthCloseAt();
        };
        Instant end = switch (inSession) {
          case PRE -> d.rthOpenAt();
          case REGULAR -> d.rthCloseAt();
          default -> d.postCloseAt();
        };
        SessionType nextType = switch (inSession) {
          case PRE -> SessionType.REGULAR;
          // a zero-length POST (always-open calendar) is skipped
          case REGULAR -> d.postCloseAt().isAfter(d.rthCloseAt()) ? SessionType.POST : SessionType.CLOSED;
          default -> SessionType.CLOSED;
        };
        return new SessionState(inSession, d.tradeDate(), start, end, nextType, end);
      }
    }
    // CLOSED: from the last post-close (or the start of this date) until the next session start.
    SessionTransition next = nextSessionStart(t);
    Instant startedAt = lastPostCloseAtOrBefore(t);
    TradingDay nextDay = day(next.tradingDate()).orElseThrow();
    SessionType nextType = nextDay.rthOpenAt().isAfter(nextDay.preOpenAt()) ? SessionType.PRE : SessionType.REGULAR;
    return new SessionState(SessionType.CLOSED, today.map(TradingDay::tradeDate).orElse(null), startedAt, next.at(),
        nextType, next.at());
  }

  @Override
  public SessionTransition nextTransitionAfter(Instant t) {
    LocalDate d = Et.date(t).minusDays(1);           // a session can straddle midnight ET only in theory
    for (int i = 0; i < MAX_SCAN_DAYS; i++, d = d.plusDays(1)) {
      Optional<TradingDay> day = day(d);
      if (day.isEmpty()) {
        continue;
      }
      for (SessionTransition tr : transitionsOf(day.get())) {
        if (tr.at().isAfter(t)) {
          return tr;
        }
      }
    }
    throw new IllegalStateException("No session transition within " + MAX_SCAN_DAYS + " days after " + t);
  }

  @Override
  public List<SessionTransition> transitionsBetween(Instant fromExclusive, Instant toInclusive) {
    List<SessionTransition> out = new ArrayList<>();
    if (!toInclusive.isAfter(fromExclusive)) {
      return out;
    }
    LocalDate last = Et.date(toInclusive);
    for (LocalDate d = Et.date(fromExclusive).minusDays(1); !d.isAfter(last); d = d.plusDays(1)) {
      day(d).ifPresent(day -> {
        for (SessionTransition tr : transitionsOf(day)) {
          if (tr.at().isAfter(fromExclusive) && !tr.at().isAfter(toInclusive)) {
            out.add(tr);
          }
        }
      });
    }
    return out;
  }

  /** The four transitions of a trading day, in time order. */
  public static List<SessionTransition> transitionsOf(TradingDay d) {
    return List.of(
        new SessionTransition(d.tradeDate(), TransitionType.PRE_OPEN, d.preOpenAt()),
        new SessionTransition(d.tradeDate(), TransitionType.RTH_OPEN, d.rthOpenAt()),
        new SessionTransition(d.tradeDate(), TransitionType.RTH_CLOSE, d.rthCloseAt()),
        new SessionTransition(d.tradeDate(), TransitionType.POST_CLOSE, d.postCloseAt()));
  }

  /** PRE, REGULAR or POST when {@code t} lies inside a non-empty session of {@code d}; null otherwise. */
  static SessionType sessionWithin(TradingDay d, Instant t) {
    if (t.isBefore(d.preOpenAt()) || !t.isBefore(d.postCloseAt())) {
      return null;
    }
    if (t.isBefore(d.rthOpenAt())) {
      return SessionType.PRE;
    }
    if (t.isBefore(d.rthCloseAt())) {
      return SessionType.REGULAR;
    }
    return SessionType.POST;
  }

  /** The next PRE_OPEN strictly after {@code t} (the start of the next trading day's first session). */
  private SessionTransition nextSessionStart(Instant t) {
    Instant cursor = t;
    for (int i = 0; i < 8; i++) {
      SessionTransition next = nextTransitionAfter(cursor);
      if (next.type() == TransitionType.PRE_OPEN) {
        return next;
      }
      cursor = next.at();
    }
    return nextTransitionAfter(t);
  }

  private Instant lastPostCloseAtOrBefore(Instant t) {
    LocalDate d = Et.date(t);
    for (int i = 0; i < MAX_SCAN_DAYS; i++, d = d.minusDays(1)) {
      Optional<TradingDay> day = day(d);
      if (day.isPresent() && !day.get().postCloseAt().isAfter(t)) {
        return day.get().postCloseAt();
      }
    }
    return Et.startOfDay(Et.date(t));
  }
}
