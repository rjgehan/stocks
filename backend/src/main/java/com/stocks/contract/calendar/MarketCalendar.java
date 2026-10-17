package com.stocks.contract.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The ONLY way to read trading days (§9.3, WS2). All dates are ET trading dates. */
public interface MarketCalendar {

  Optional<TradingDay> day(LocalDate date);

  boolean isTradingDay(LocalDate date);

  /** The next trading day strictly after {@code date}. */
  LocalDate nextTradingDay(LocalDate date);

  /** The previous trading day strictly before {@code date}. */
  LocalDate previousTradingDay(LocalDate date);

  LocalDate tradingDayOnOrBefore(LocalDate date);

  /**
   * T+1 settlement date, bank-holiday aware (§10).
   *
   * @throws IllegalArgumentException if {@code tradeDate} is not a trading day
   */
  LocalDate settlementDate(LocalDate tradeDate);

  SessionState stateAt(Instant t);

  SessionTransition nextTransitionAfter(Instant t);

  List<SessionTransition> transitionsBetween(Instant fromExclusive, Instant toInclusive);

  /** Phase 1: the ET calendar date of the instant. */
  LocalDate tradeDateFor(Instant executionTime);

  /** §16.3: the latest trading day whose {@code pre_open_at <= t}. */
  LocalDate currentPnlDay(Instant t);

  /**
   * The calendar {@code symbol} trades on (§29): the 24/7 {@link CryptoCalendar} for a crypto pair
   * ({@code BTC/USD}), this calendar for everything else.
   */
  default MarketCalendar forSymbol(String symbol) {
    return com.stocks.common.domain.Crypto.isCrypto(symbol) ? CryptoCalendar.INSTANCE : this;
  }
}
