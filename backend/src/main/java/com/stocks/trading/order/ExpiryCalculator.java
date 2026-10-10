package com.stocks.trading.order;

import com.stocks.common.domain.TimeInForce;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.trading.config.RulesProperties;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code session_date} and {@code expires_at} (§11.4):
 * <pre>
 * today         = ET date of accepted_at
 * sessionEnd(d) = ext ? post_close_at(d) : rth_close_at(d)
 * session_date  = (isTradingDay(today) AND accepted_at &lt; sessionEnd(today)) ? today : nextTradingDay(today)
 * expires_at    = DAY → sessionEnd(session_date);  GTC → sessionEnd(tradingDayOnOrBefore(today + gtcDays))
 * </pre>
 * Early closes come from the calendar ({@code rth_close_at} 13:00, {@code post_close_at} 17:00). Because
 * {@code expires_at} is stored, expiry is restart-safe.
 */
public final class ExpiryCalculator {

  public record Expiry(LocalDate sessionDate, Instant expiresAt) {}

  private ExpiryCalculator() {}

  public static Expiry compute(MarketCalendar calendar, RulesProperties rules, Instant acceptedAt, boolean ext,
                               TimeInForce tif) {
    LocalDate today = Et.date(acceptedAt);
    LocalDate sessionDate = calendar.isTradingDay(today) && acceptedAt.isBefore(sessionEnd(calendar, today, ext))
        ? today : calendar.nextTradingDay(today);
    if (tif == TimeInForce.GTC) {
      LocalDate last = calendar.tradingDayOnOrBefore(today.plusDays(rules.gtcDays()));
      if (last.isBefore(sessionDate)) {
        last = sessionDate;
      }
      return new Expiry(sessionDate, sessionEnd(calendar, last, ext));
    }
    return new Expiry(sessionDate, sessionEnd(calendar, sessionDate, ext));
  }

  /** {@code post_close_at} for extended-hours orders, else {@code rth_close_at}. */
  public static Instant sessionEnd(MarketCalendar calendar, LocalDate date, boolean ext) {
    TradingDay d = calendar.day(date)
        .orElseThrow(() -> new IllegalStateException(date + " is not a trading day"));
    return ext ? d.postCloseAt() : d.rthCloseAt();
  }
}
