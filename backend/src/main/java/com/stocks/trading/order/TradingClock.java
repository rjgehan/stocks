package com.stocks.trading.order;

import com.stocks.common.domain.SessionType;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * "Now" and the market session as trading sees them: {@link TimeSource} plus WS2's {@link MarketCalendar}. If the
 * calendar cannot answer (a Day-0 stub, an outage), the session is treated as {@code CLOSED}: nothing works or fills,
 * and the engine loop keeps running instead of failing on every sweep.
 */
@Component
public class TradingClock {

  private static final Logger log = LoggerFactory.getLogger(TradingClock.class);

  private final TimeSource time;
  private final MarketCalendar calendar;
  private final AtomicBoolean warned = new AtomicBoolean();

  public TradingClock(TimeSource time, MarketCalendar calendar) {
    this.time = time;
    this.calendar = calendar;
  }

  public Instant now() {
    return time.now();
  }

  public MarketCalendar calendar() {
    return calendar;
  }

  /** The session at {@code t}; CLOSED (with no trading date) when the calendar is unavailable. */
  public SessionState session(Instant t) {
    try {
      return calendar.stateAt(t);
    } catch (RuntimeException e) {
      if (warned.compareAndSet(false, true)) {
        log.warn("Market calendar unavailable ({}); trading treats the market as CLOSED", e.getMessage());
      }
      return new SessionState(SessionType.CLOSED, null, t, null, SessionType.CLOSED, null);
    }
  }

  /** The calendar {@code symbol} trades on: 24/7 for crypto pairs (§29), the NYSE calendar otherwise. */
  public MarketCalendar calendar(String symbol) {
    return calendar.forSymbol(symbol);
  }

  /** The session of {@code symbol} at {@code t}: always REGULAR for a crypto pair (§29). */
  public SessionState session(String symbol, Instant t) {
    MarketCalendar c = calendar.forSymbol(symbol);
    return c == calendar ? session(t) : c.stateAt(t);
  }

  /** {@code calendar.tradeDateFor(t)}, falling back to the ET date of {@code t}. */
  public LocalDate tradeDate(Instant t) {
    try {
      return calendar.tradeDateFor(t);
    } catch (RuntimeException e) {
      return Et.date(t);
    }
  }

  /** T+1 (bank-holiday aware) settlement of a trade date (§9). */
  public LocalDate settlementDate(LocalDate tradeDate) {
    return calendar.settlementDate(tradeDate);
  }

  /** The settlement date of a trade in {@code symbol}: the trade date itself for a crypto pair (§29), else T+1. */
  public LocalDate settlementDate(String symbol, LocalDate tradeDate) {
    return calendar.forSymbol(symbol).settlementDate(tradeDate);
  }
}
