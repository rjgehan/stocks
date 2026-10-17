package com.stocks.contract.calendar;

import com.stocks.common.domain.SessionType;
import com.stocks.common.time.Et;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The 24/7 calendar of crypto pairs (§29): every ET date is a trading day whose REGULAR session runs from 00:00 to
 * the next 00:00 ET (PRE and POST are empty), trades settle on the trade date, and there are no session transitions,
 * so nothing ever pauses or queues for an open. Pure and stateless; obtained through
 * {@link MarketCalendar#forSymbol(String)}, so stock symbols keep the NYSE calendar.
 */
public final class CryptoCalendar implements MarketCalendar {

  public static final CryptoCalendar INSTANCE = new CryptoCalendar();

  /** {@link TradingDay#source()} of crypto days. */
  public static final String SOURCE = "crypto";

  private CryptoCalendar() {}

  @Override
  public Optional<TradingDay> day(LocalDate date) {
    Instant start = Et.startOfDay(date);
    Instant end = Et.startOfDay(date.plusDays(1));
    return Optional.of(new TradingDay(date, start, start, end, end, date, false, SOURCE));
  }

  @Override
  public boolean isTradingDay(LocalDate date) {
    return true;
  }

  @Override
  public LocalDate nextTradingDay(LocalDate date) {
    return date.plusDays(1);
  }

  @Override
  public LocalDate previousTradingDay(LocalDate date) {
    return date.minusDays(1);
  }

  @Override
  public LocalDate tradingDayOnOrBefore(LocalDate date) {
    return date;
  }

  /** Crypto settles on the trade date (no T+1). */
  @Override
  public LocalDate settlementDate(LocalDate tradeDate) {
    return tradeDate;
  }

  @Override
  public SessionState stateAt(Instant t) {
    LocalDate d = Et.date(t);
    Instant start = Et.startOfDay(d);
    Instant end = Et.startOfDay(d.plusDays(1));
    return new SessionState(SessionType.REGULAR, d, start, end, SessionType.REGULAR, end);
  }

  /** There are no session edges; the next ET midnight stands in (it changes nothing). */
  @Override
  public SessionTransition nextTransitionAfter(Instant t) {
    LocalDate next = Et.date(t).plusDays(1);
    return new SessionTransition(next, com.stocks.common.domain.TransitionType.RTH_OPEN, Et.startOfDay(next));
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
    return Et.date(t);
  }

  @Override
  public MarketCalendar forSymbol(String symbol) {
    return this;
  }

  @Override
  public String toString() {
    return "CryptoCalendar";
  }
}
