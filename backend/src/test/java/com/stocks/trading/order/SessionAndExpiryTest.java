package com.stocks.trading.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.WarningCode;
import com.stocks.common.time.Et;
import com.stocks.trading.config.RulesProperties;
import com.stocks.trading.testkit.TestCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** §11.3 session acceptance and §11.4 session date and expiry, including early closes and DST. */
class SessionAndExpiryTest {

  final TestCalendar cal = new TestCalendar();
  final RulesProperties rules = RulesProperties.defaults();

  static Instant et(String date, String time) {
    return Et.at(LocalDate.parse(date), LocalTime.parse(time));
  }

  SessionOrderMatrix.Acceptance accept(OrderType type, boolean ext, TimeInForce tif, Instant now) {
    return SessionOrderMatrix.accept(cal, type, ext, tif, cal.stateAt(now), now);
  }

  @Test
  void regularHoursWorkAfterLatency() {
    Instant now = et("2026-10-09", "10:00");
    var a = accept(OrderType.MARKET, false, TimeInForce.DAY, now);
    assertThat(a.waitReason()).isEqualTo(WaitReason.LATENCY);
    assertThat(a.eligibleBase()).isEqualTo(now);
    assertThat(a.queued()).isFalse();
    assertThat(SessionOrderMatrix.sessionNote(a)).isNull();
  }

  @Test
  void marketAndPlainLimitsQueueForTheOpenOutsideRegularHours() {
    // PRE: today's open
    var pre = accept(OrderType.MARKET, false, TimeInForce.DAY, et("2026-10-09", "05:00"));
    assertThat(pre.waitReason()).isEqualTo(WaitReason.QUEUED_FOR_OPEN);
    assertThat(pre.eligibleBase()).isEqualTo(et("2026-10-09", "09:30"));
    assertThat(pre.warnings()).extracting(w -> w.code()).contains(WarningCode.QUEUED_FOR_OPEN.name());
    assertThat(SessionOrderMatrix.sessionNote(pre)).isEqualTo("Order will queue for 09:30 ET");
    // POST: next trading day's open (Columbus Day is a trading day)
    var post = accept(OrderType.LIMIT, false, TimeInForce.DAY, et("2026-10-09", "17:00"));
    assertThat(post.eligibleBase()).isEqualTo(et("2026-10-12", "09:30"));
    // a Saturday
    var sat = accept(OrderType.MARKET, false, TimeInForce.DAY, et("2026-10-10", "12:00"));
    assertThat(sat.eligibleBase()).isEqualTo(et("2026-10-12", "09:30"));
  }

  @Test
  void extendedHoursLimitsWorkInPreAndPostAndQueueForThePreOpen() {
    assertThat(accept(OrderType.LIMIT, true, TimeInForce.DAY, et("2026-10-09", "05:00")).waitReason())
        .isEqualTo(WaitReason.LATENCY);
    assertThat(accept(OrderType.LIMIT, true, TimeInForce.DAY, et("2026-10-09", "19:00")).waitReason())
        .isEqualTo(WaitReason.LATENCY);
    var closed = accept(OrderType.LIMIT, true, TimeInForce.GTC, et("2026-10-09", "21:00"));
    assertThat(closed.waitReason()).isEqualTo(WaitReason.QUEUED_FOR_EXT_OPEN);
    assertThat(closed.eligibleBase()).isEqualTo(et("2026-10-12", "04:00"));
    assertThat(SessionOrderMatrix.sessionNote(closed)).isEqualTo("Order will queue for 04:00 ET");
    var early = accept(OrderType.LIMIT, true, TimeInForce.DAY, et("2026-10-09", "02:00"));
    assertThat(early.eligibleBase()).isEqualTo(et("2026-10-09", "04:00"));
  }

  @Test
  void gtcWithoutExtendedHoursWarnsItWillPause() {
    var a = accept(OrderType.LIMIT, false, TimeInForce.GTC, et("2026-10-09", "10:00"));
    assertThat(a.warnings()).extracting(w -> w.code()).containsExactly(WarningCode.WILL_PAUSE_OUTSIDE_SESSION.name());
  }

  @Test
  void trailingStopsAreRegularHoursOnly() {
    assertThatThrownBy(() -> accept(OrderType.TRAILING_STOP, false, TimeInForce.DAY, et("2026-10-09", "17:00")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SESSION_NOT_ALLOWED));
    assertThat(accept(OrderType.TRAILING_STOP, false, TimeInForce.DAY, et("2026-10-09", "10:00")).waitReason())
        .isEqualTo(WaitReason.LATENCY);
  }

  @Test
  void windows() {
    assertThat(SessionOrderMatrix.windowOpen(false, SessionType.REGULAR)).isTrue();
    assertThat(SessionOrderMatrix.windowOpen(false, SessionType.PRE)).isFalse();
    assertThat(SessionOrderMatrix.windowOpen(true, SessionType.POST)).isTrue();
    assertThat(SessionOrderMatrix.windowOpen(true, SessionType.CLOSED)).isFalse();
  }

  // ------------------------------------------------------------------------------------------- expiry

  @Test
  void dayOrdersExpireAtTheCloseOfTheirSessionDate() {
    var e = ExpiryCalculator.compute(cal, rules, et("2026-10-09", "10:00"), false, TimeInForce.DAY);
    assertThat(e.sessionDate()).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(e.expiresAt()).isEqualTo(et("2026-10-09", "16:00"));
    var ext = ExpiryCalculator.compute(cal, rules, et("2026-10-09", "10:00"), true, TimeInForce.DAY);
    assertThat(ext.expiresAt()).isEqualTo(et("2026-10-09", "20:00"));
    var after = ExpiryCalculator.compute(cal, rules, et("2026-10-09", "16:30"), false, TimeInForce.DAY);
    assertThat(after.sessionDate()).isEqualTo(LocalDate.of(2026, 10, 12));
    assertThat(after.expiresAt()).isEqualTo(et("2026-10-12", "16:00"));
    var extAfter = ExpiryCalculator.compute(cal, rules, et("2026-10-09", "16:30"), true, TimeInForce.DAY);
    assertThat(extAfter.sessionDate()).isEqualTo(LocalDate.of(2026, 10, 9));
  }

  @Test
  void earlyClosesEndTheDayAt1300And1700() {
    var e = ExpiryCalculator.compute(cal, rules, et("2026-11-27", "10:00"), false, TimeInForce.DAY);
    assertThat(e.expiresAt()).isEqualTo(et("2026-11-27", "13:00"));
    var ext = ExpiryCalculator.compute(cal, rules, et("2026-11-27", "10:00"), true, TimeInForce.DAY);
    assertThat(ext.expiresAt()).isEqualTo(et("2026-11-27", "17:00"));
  }

  @Test
  void gtcLastsSixtyDaysToTheLastTradingDay() {
    var e = ExpiryCalculator.compute(cal, rules, et("2026-10-09", "10:00"), false, TimeInForce.GTC);
    // 2026-10-09 + 60 = 2026-12-08 (a Tuesday)
    assertThat(e.expiresAt()).isEqualTo(et("2026-12-08", "16:00"));
    // Wed 10-07 + 60 = Sun 12-06 → the trading day on or before it, Fri 12-04
    var weekend = ExpiryCalculator.compute(cal, rules, et("2026-10-07", "10:00"), false, TimeInForce.GTC);
    assertThat(weekend.expiresAt()).isEqualTo(et("2026-12-04", "16:00"));
  }

  @Test
  void dstSwitchesKeepEtWallTimes() {
    // 2026-11-01 (fall back) and 2027-03-14 (spring forward) are Sundays: the next session opens at 09:30 ET
    var fall = accept(OrderType.MARKET, false, TimeInForce.DAY, et("2026-11-01", "12:00"));
    assertThat(fall.eligibleBase()).isEqualTo(Instant.parse("2026-11-02T14:30:00Z"));
    var spring = accept(OrderType.MARKET, false, TimeInForce.DAY, et("2027-03-14", "12:00"));
    assertThat(spring.eligibleBase()).isEqualTo(Instant.parse("2027-03-15T13:30:00Z"));
  }

  @Test
  void aMissingTradingDayIsAnError() {
    assertThatThrownBy(() -> ExpiryCalculator.sessionEnd(cal, LocalDate.of(2026, 10, 10), false))
        .isInstanceOf(IllegalStateException.class);
  }
}
