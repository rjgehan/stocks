package com.stocks.trading.order;

import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.Warning;
import com.stocks.common.error.WarningCode;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.CryptoCalendar;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.calendar.TradingDay;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Session acceptance (§11.3). Every accepted order is persisted as {@code PENDING}; it becomes {@code WORKING} once
 * {@code now ≥ eligible_at} and its window is open.
 * <table>
 *   <caption>Acceptance</caption>
 *   <tr><th>Order</th><th>CLOSED</th><th>PRE</th><th>REGULAR</th><th>POST</th></tr>
 *   <tr><td>MARKET, STOP, STOP_LIMIT, LIMIT ext=false</td><td>QUEUED_FOR_OPEN</td><td>QUEUED_FOR_OPEN</td>
 *       <td>LATENCY</td><td>QUEUED_FOR_OPEN (next day)</td></tr>
 *   <tr><td>TRAILING_STOP</td><td>rejected</td><td>rejected</td><td>LATENCY</td><td>rejected</td></tr>
 *   <tr><td>LIMIT ext=true</td><td>QUEUED_FOR_EXT_OPEN</td><td>LATENCY</td><td>LATENCY</td><td>LATENCY</td></tr>
 * </table>
 * The returned {@code eligibleBase} is the instant before the order's latency is added.
 */
public final class SessionOrderMatrix {

  /** {@code queued}: the order waits for a session open (arriving semantics at the open, §12.6). */
  public record Acceptance(Instant eligibleBase, WaitReason waitReason, boolean queued, List<Warning> warnings) {}

  private SessionOrderMatrix() {}

  /** The window in which an order works: REGULAR, plus PRE and POST for extended-hours limits. */
  public static boolean windowOpen(boolean extendedHours, SessionType session) {
    return session == SessionType.REGULAR
        || (extendedHours && (session == SessionType.PRE || session == SessionType.POST));
  }

  public static Acceptance accept(MarketCalendar calendar, OrderType type, boolean ext, TimeInForce tif,
                                  SessionState state, Instant now) {
    SessionType session = state.type();
    List<Warning> warnings = new ArrayList<>();
    if (type == OrderType.TRAILING_STOP && session != SessionType.REGULAR) {
      throw ApiException.of(ErrorCode.SESSION_NOT_ALLOWED,
              "Trailing stops can only be entered during regular hours (09:30–16:00 ET).")
          .withHint("Enter the trailing stop after the 09:30 ET open.");
    }
    if (tif == TimeInForce.GTC && !ext && !(calendar instanceof CryptoCalendar)) {   // crypto never pauses (§29)
      warnings.add(Warning.of(WarningCode.WILL_PAUSE_OUTSIDE_SESSION,
          "A GTC order without extended hours pauses outside the regular session."));
    }
    if (windowOpen(ext, session)) {
      return new Acceptance(now, WaitReason.LATENCY, false, warnings);
    }
    if (ext) {
      Instant at = nextPreOpen(calendar, now);
      warnings.add(Warning.of(WarningCode.QUEUED_FOR_EXT_OPEN, "Order will queue for the " + when(at, now)
          + " pre-market open."));
      return new Acceptance(at, WaitReason.QUEUED_FOR_EXT_OPEN, true, warnings);
    }
    Instant at = nextRegularOpen(calendar, now);
    warnings.add(Warning.of(WarningCode.QUEUED_FOR_OPEN, "Order will queue for the " + when(at, now) + " open."));
    return new Acceptance(at, WaitReason.QUEUED_FOR_OPEN, true, warnings);
  }

  /** The next {@code rth_open_at} at or after {@code now}. */
  public static Instant nextRegularOpen(MarketCalendar calendar, Instant now) {
    LocalDate d = Et.date(now);
    if (calendar.isTradingDay(d)) {
      TradingDay day = calendar.day(d).orElseThrow();
      if (!now.isAfter(day.rthOpenAt())) {
        return day.rthOpenAt();
      }
    }
    return calendar.day(calendar.nextTradingDay(d)).orElseThrow().rthOpenAt();
  }

  /** The next {@code pre_open_at} at or after {@code now}. */
  public static Instant nextPreOpen(MarketCalendar calendar, Instant now) {
    LocalDate d = Et.date(now);
    if (calendar.isTradingDay(d)) {
      TradingDay day = calendar.day(d).orElseThrow();
      if (!now.isAfter(day.preOpenAt())) {
        return day.preOpenAt();
      }
    }
    return calendar.day(calendar.nextTradingDay(d)).orElseThrow().preOpenAt();
  }

  /**
   * A human note for previews ("Order will queue for 09:30 ET", or "… for Mon 09:30 ET" when the open is not today),
   * or null when the order works now.
   */
  public static String sessionNote(Acceptance a, Instant now) {
    if (a.waitReason() == WaitReason.QUEUED_FOR_OPEN || a.waitReason() == WaitReason.QUEUED_FOR_EXT_OPEN) {
      return "Order will queue for " + when(a.eligibleBase(), now);
    }
    return null;
  }

  private static final java.time.format.DateTimeFormatter WEEKDAY =
      java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.US);
  private static final java.time.format.DateTimeFormatter HH_MM =
      java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US);

  /** "09:30 ET" today, else "Mon 09:30 ET" (a Saturday order waits for Monday). */
  static String when(Instant at, Instant now) {
    String time = Et.time(at).format(HH_MM) + " ET";
    LocalDate day = Et.date(at);
    return day.equals(Et.date(now)) ? time : day.format(WEEKDAY) + " " + time;
  }
}
