package com.stocks.portfolio;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.identity.SettingsService;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The P&amp;L-day view of the market calendar that valuation, snapshots and the leaderboard share (§16.3): P&amp;L day D
 * runs from the previous trading day's cut-off to {@code day(D).postCloseAt()} (20:00 ET, 17:00 on early closes).
 * Everything goes through {@link MarketCalendar}, never SQL on the calendar table (§8).
 *
 * <p>While WS2's calendar is still the Day-0 stub (501 {@code NOT_IMPLEMENTED}) it falls back to a plain weekday
 * calendar (Mon–Fri, 04:00 pre-open, 20:00 cut-off ET, no holidays), so the league works before the calendar lands;
 * the real calendar always wins once it exists.
 *
 * <p><b>Every calendar day (§29).</b> While {@code crypto.enabled} is on, crypto pairs move on weekends and holidays,
 * so every calendar day is a P&amp;L day: a non-trading day runs from the previous day's cut-off to 20:00 ET
 * (pre-open 04:00), and EOD snapshots, intraday samples, the P&amp;L calendar and the TODAY anchor follow. Trading days
 * keep their own pre-open and cut-off (17:00 on early closes).
 */
@Component
public class PnlCalendar {

  private static final Logger log = LoggerFactory.getLogger(PnlCalendar.class);

  static final LocalTime FALLBACK_PRE_OPEN = LocalTime.of(4, 0);
  static final LocalTime FALLBACK_CUTOFF = LocalTime.of(20, 0);
  /** Guards the trading-day walks against a calendar with no trading days. */
  private static final int MAX_WALK_DAYS = 400;

  private final MarketCalendar calendar;
  private final BooleanSupplier everyDay;
  private final AtomicBoolean warned = new AtomicBoolean();

  /** NYSE trading days only (tests and tools). */
  public PnlCalendar(MarketCalendar calendar) {
    this(calendar, () -> false);
  }

  /** The league's calendar: every calendar day is a P&amp;L day while {@code crypto.enabled} is on (§29). */
  @Autowired
  public PnlCalendar(MarketCalendar calendar, SettingsService settings) {
    this(calendar, () -> cryptoEnabled(settings));
  }

  public PnlCalendar(MarketCalendar calendar, BooleanSupplier everyDay) {
    this.calendar = Objects.requireNonNull(calendar, "calendar");
    this.everyDay = Objects.requireNonNull(everyDay, "everyDay");
  }

  private static boolean cryptoEnabled(SettingsService settings) {
    try {
      return settings.cryptoEnabled();
    } catch (ApiException e) {
      return true;                                   // the §23.4 default while settings are a Day-0 stub
    }
  }

  /** True while every calendar day is a P&amp;L day (crypto trading is on, §29). */
  public boolean everyDay() {
    return everyDay.getAsBoolean();
  }

  /** §16.3: the latest trading day whose pre-open is at or before {@code t}. */
  public LocalDate currentPnlDay(Instant t) {
    if (everyDay()) {
      LocalDate d = Et.date(t);
      return preOpen(d).isAfter(t) ? d.minusDays(1) : d;
    }
    try {
      return calendar.currentPnlDay(t);
    } catch (ApiException e) {
      stub(e);
      LocalDate d = Et.date(t);
      if (isWeekday(d) && !Et.at(d, FALLBACK_PRE_OPEN).isAfter(t)) {
        return d;
      }
      return fallbackPrevious(d);
    }
  }

  public boolean isTradingDay(LocalDate date) {
    if (everyDay()) {
      return true;
    }
    try {
      return calendar.isTradingDay(date);
    } catch (ApiException e) {
      stub(e);
      return isWeekday(date);
    }
  }

  /** The trading day strictly before {@code date}. */
  public LocalDate previousTradingDay(LocalDate date) {
    if (everyDay()) {
      return date.minusDays(1);
    }
    try {
      return calendar.previousTradingDay(date);
    } catch (ApiException e) {
      stub(e);
      return fallbackPrevious(date);
    }
  }

  /** The trading day strictly after {@code date}. */
  public LocalDate nextTradingDay(LocalDate date) {
    if (everyDay()) {
      return date.plusDays(1);
    }
    try {
      return calendar.nextTradingDay(date);
    } catch (ApiException e) {
      stub(e);
      LocalDate d = date.plusDays(1);
      while (!isWeekday(d)) {
        d = d.plusDays(1);
      }
      return d;
    }
  }

  public LocalDate tradingDayOnOrBefore(LocalDate date) {
    if (everyDay()) {
      return date;
    }
    try {
      return calendar.tradingDayOnOrBefore(date);
    } catch (ApiException e) {
      stub(e);
      return isWeekday(date) ? date : fallbackPrevious(date);
    }
  }

  /** The P&amp;L cut-off of trading day {@code date}: {@code postCloseAt} (§16.3, §16.4). */
  public Instant cutoff(LocalDate date) {
    return day(date).map(TradingDay::postCloseAt).orElseGet(() -> Et.at(date, FALLBACK_CUTOFF));
  }

  /** {@code preOpenAt} of trading day {@code date}. */
  public Instant preOpen(LocalDate date) {
    return day(date).map(TradingDay::preOpenAt).orElseGet(() -> Et.at(date, FALLBACK_PRE_OPEN));
  }

  /** Trading days in {@code (fromExclusive, toInclusive]}, oldest first. */
  public List<LocalDate> tradingDaysBetween(LocalDate fromExclusive, LocalDate toInclusive) {
    List<LocalDate> out = new ArrayList<>();
    if (!toInclusive.isAfter(fromExclusive)) {
      return out;
    }
    LocalDate d = fromExclusive.plusDays(1);
    while (!d.isAfter(toInclusive)) {
      if (isTradingDay(d)) {
        out.add(d);
      }
      d = d.plusDays(1);
    }
    return out;
  }

  /** True while the calendar is the Day-0 stub and this class answers from its weekday fallback. */
  public boolean usingFallback() {
    try {
      calendar.isTradingDay(LocalDate.of(2026, 1, 2));
      return false;
    } catch (ApiException e) {
      return e.code() == ErrorCode.NOT_IMPLEMENTED;
    }
  }

  private Optional<TradingDay> day(LocalDate date) {
    try {
      return calendar.day(date);
    } catch (ApiException e) {
      stub(e);
      return Optional.empty();
    }
  }

  private void stub(ApiException e) {
    if (e.code() != ErrorCode.NOT_IMPLEMENTED) {
      throw e;
    }
    if (warned.compareAndSet(false, true)) {
      log.warn("MarketCalendar is not implemented yet; P&L days use a Mon-Fri fallback (04:00 pre-open, 20:00 ET "
          + "cut-off, no holidays)");
    }
  }

  private static boolean isWeekday(LocalDate d) {
    DayOfWeek w = d.getDayOfWeek();
    return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY;
  }

  private static LocalDate fallbackPrevious(LocalDate date) {
    LocalDate d = date.minusDays(1);
    int guard = 0;
    while (!isWeekday(d) && guard++ < MAX_WALK_DAYS) {
      d = d.minusDays(1);
    }
    return d;
  }
}
