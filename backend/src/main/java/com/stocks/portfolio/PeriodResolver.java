package com.stocks.portfolio;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.LeaderboardPeriod;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import org.springframework.stereotype.Component;

/**
 * Period anchors (§16.3, ET). For P&amp;L day {@code D = currentPnlDay(now)} each period starts at the EOD cut-off of
 * an anchor trading day {@code Da}; {@code ALL} (and {@code HistoryRange.ALL}) starts at the player's first run
 * opening, so it has no anchor date.
 *
 * <table>
 *   <caption>Leaderboard periods</caption>
 *   <tr><td>TODAY</td><td>{@code previousTradingDay(D)}</td></tr>
 *   <tr><td>WEEK</td><td>the trading day before the first trading day of D's ISO week</td></tr>
 *   <tr><td>MONTH</td><td>the last trading day of the previous calendar month</td></tr>
 *   <tr><td>YTD</td><td>the last trading day of the previous year</td></tr>
 *   <tr><td>SEASON</td><td>the last trading day before {@code league.seasonStart} (never after TODAY's anchor)</td></tr>
 * </table>
 * History ranges ({@code GET /account/history}): {@code 1D} = TODAY, {@code YTD} = YTD, and {@code 1W}/{@code 1M}/
 * {@code 3M}/{@code 1Y} = the last trading day on or before D minus 7 days / 1 / 3 / 12 months.
 */
@Component
public class PeriodResolver {

  /** A period window: anchor trading day (null for ALL) and its cut-off instant (null for ALL). */
  public record Window(LocalDate anchorDate, Instant anchorAt) {
    public boolean sinceOpening() {
      return anchorDate == null;
    }
  }

  private final PnlCalendar calendar;
  private final LeagueSettings settings;

  public PeriodResolver(PnlCalendar calendar, LeagueSettings settings) {
    this.calendar = calendar;
    this.settings = settings;
  }

  /** The anchor trading day of {@code period} for P&amp;L day {@code d}; null for ALL. */
  public LocalDate anchorDate(LeaderboardPeriod period, LocalDate d) {
    LocalDate today = calendar.previousTradingDay(d);
    LocalDate anchor = switch (period) {
      case TODAY -> today;
      case WEEK -> {
        LocalDate monday = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstTradingDay = calendar.isTradingDay(monday) ? monday : calendar.nextTradingDay(monday);
        if (firstTradingDay.isAfter(d)) {
          firstTradingDay = d;
        }
        yield calendar.previousTradingDay(firstTradingDay);
      }
      case MONTH -> calendar.tradingDayOnOrBefore(d.withDayOfMonth(1).minusDays(1));
      case YTD -> calendar.tradingDayOnOrBefore(LocalDate.of(d.getYear() - 1, 12, 31));
      case SEASON -> calendar.tradingDayOnOrBefore(settings.seasonStart().minusDays(1));
      case ALL -> null;
    };
    if (anchor != null && anchor.isAfter(today)) {
      anchor = today;                                // a season that has not started yet behaves like TODAY
    }
    return anchor;
  }

  public Window window(LeaderboardPeriod period, LocalDate d) {
    LocalDate anchor = anchorDate(period, d);
    return new Window(anchor, anchor == null ? null : calendar.cutoff(anchor));
  }

  /** The anchor trading day of a history range for P&amp;L day {@code d}; null for ALL (the run opening). */
  public LocalDate anchorDate(HistoryRange range, LocalDate d) {
    LocalDate anchor = switch (range) {
      case D1 -> anchorDate(LeaderboardPeriod.TODAY, d);
      case W1 -> calendar.tradingDayOnOrBefore(d.minusDays(7));
      case M1 -> calendar.tradingDayOnOrBefore(d.minusMonths(1));
      case M3 -> calendar.tradingDayOnOrBefore(d.minusMonths(3));
      case YTD -> anchorDate(LeaderboardPeriod.YTD, d);
      case Y1 -> calendar.tradingDayOnOrBefore(d.minusYears(1));
      case ALL -> null;
    };
    LocalDate today = calendar.previousTradingDay(d);
    return anchor != null && anchor.isAfter(today) ? today : anchor;
  }

  public Window window(HistoryRange range, LocalDate d) {
    LocalDate anchor = anchorDate(range, d);
    return new Window(anchor, anchor == null ? null : calendar.cutoff(anchor));
  }
}
