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
}
