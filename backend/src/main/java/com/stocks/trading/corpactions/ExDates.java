package com.stocks.trading.corpactions;

import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * When corporate actions happen (§14, §25), pure apart from the calendar:
 * <ul>
 *   <li>actions are applied at 03:45 ET on their ex-date, a day's NAME_CHANGEs first, then splits, then dividends,
 *       then the rest ({@link #order});</li>
 *   <li>the <b>ex cut-off</b> is the ex-date's pre-open (04:00 ET): there is no overnight session, so positions before
 *       it are the end-of-previous-day positions, which under T+1 are the entitled ones.</li>
 * </ul>
 */
public final class ExDates {

  static final LocalTime CUTOFF = LocalTime.of(4, 0);

  private ExDates() {}

  /** NAME_CHANGE 0, splits 1, dividends 2, everything else 3 (§14 order of application). */
  static int order(CorporateActionType type) {
    return switch (type) {
      case NAME_CHANGE -> 0;
      case FORWARD_SPLIT, REVERSE_SPLIT -> 1;
      case CASH_DIVIDEND -> 2;
      default -> 3;
    };
  }

  /** The pre-open of the ex-date (04:00 ET when the calendar does not know the day). */
  public static Instant cutoff(LocalDate exDate, MarketCalendar cal) {
    try {
      Optional<TradingDay> day = cal.day(exDate);
      if (day.isPresent()) {
        return day.get().preOpenAt();
      }
    } catch (RuntimeException e) {
      // calendar unavailable: the wall-clock pre-open
    }
    return Et.at(exDate, CUTOFF);
  }

  static LocalDate tradingDayOnOrAfter(LocalDate date, MarketCalendar cal) {
    try {
      return cal.isTradingDay(date) ? date : cal.nextTradingDay(date);
    } catch (RuntimeException e) {
      return date;
    }
  }
}
