package com.stocks.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.TransitionType;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.calendar.SessionTransition;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §9.3 session model on the bundled calendar (no database): states, transitions, DST, P&L day. */
class MarketCalendarSessionTest {

  /** The DB calendar over an empty repository falls back to the bundled CSV. */
  static final MarketCalendar CAL = InMemoryCalendars.bundled();

  @Test
  void fridayRegularSession() {
    SessionState s = CAL.stateAt(Instant.parse("2026-10-09T14:00:00Z"));       // 10:00 ET
    assertThat(s.type()).isEqualTo(SessionType.REGULAR);
    assertThat(s.tradingDate()).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(s.startedAt()).hasToString("2026-10-09T13:30:00Z");
    assertThat(s.endsAt()).hasToString("2026-10-09T20:00:00Z");
    assertThat(s.nextType()).isEqualTo(SessionType.POST);
  }

  @Test
  void preAndPostAndClosed() {
    assertThat(CAL.stateAt(Instant.parse("2026-10-09T08:00:00Z")).type()).isEqualTo(SessionType.PRE);
    assertThat(CAL.stateAt(Instant.parse("2026-10-09T07:59:59Z")).type()).isEqualTo(SessionType.CLOSED);
    assertThat(CAL.stateAt(Instant.parse("2026-10-09T20:00:00Z")).type()).isEqualTo(SessionType.POST);
    SessionState closed = CAL.stateAt(Instant.parse("2026-10-10T00:00:00Z"));   // Fri 20:00 ET
    assertThat(closed.type()).isEqualTo(SessionType.CLOSED);
    assertThat(closed.startedAt()).hasToString("2026-10-10T00:00:00Z");
    assertThat(closed.nextType()).isEqualTo(SessionType.PRE);
    assertThat(closed.nextStartsAt()).hasToString("2026-10-12T08:00:00Z");     // Columbus Day is a trading day
  }

  @Test
  void weekendIsClosedWithNoTradingDate() {
    SessionState s = CAL.stateAt(Instant.parse("2026-10-10T16:00:00Z"));
    assertThat(s.type()).isEqualTo(SessionType.CLOSED);
    assertThat(s.tradingDate()).isNull();
  }

  @Test
  void earlyCloseEndsRegularAt1300AndPostAt1700() {
    SessionState s = CAL.stateAt(Instant.parse("2026-11-27T18:30:00Z"));      // 13:30 EST
    assertThat(s.type()).isEqualTo(SessionType.POST);
    assertThat(s.endsAt()).hasToString("2026-11-27T22:00:00Z");
    assertThat(CAL.stateAt(Instant.parse("2026-11-27T22:00:00Z")).type()).isEqualTo(SessionType.CLOSED);
  }

  @Test
  void dstSwitches() {
    // 2026-11-01: EDT → EST. Friday before at 04:00 EDT = 08:00Z; Monday after at 04:00 EST = 09:00Z.
    assertThat(CAL.day(LocalDate.of(2026, 10, 30)).orElseThrow().preOpenAt()).hasToString("2026-10-30T08:00:00Z");
    assertThat(CAL.day(LocalDate.of(2026, 11, 2)).orElseThrow().preOpenAt()).hasToString("2026-11-02T09:00:00Z");
    assertThat(CAL.day(LocalDate.of(2026, 11, 2)).orElseThrow().rthOpenAt()).hasToString("2026-11-02T14:30:00Z");
    // 2027-03-14: EST → EDT.
    assertThat(CAL.day(LocalDate.of(2027, 3, 12)).orElseThrow().rthOpenAt()).hasToString("2027-03-12T14:30:00Z");
    assertThat(CAL.day(LocalDate.of(2027, 3, 15)).orElseThrow().rthOpenAt()).hasToString("2027-03-15T13:30:00Z");
    List<SessionTransition> weekend = CAL.transitionsBetween(Instant.parse("2027-03-13T06:00:00Z"),
        Instant.parse("2027-03-15T14:00:00Z"));
    assertThat(weekend).extracting(SessionTransition::type)
        .containsExactly(TransitionType.PRE_OPEN, TransitionType.RTH_OPEN);
    assertThat(weekend.get(0).at()).hasToString("2027-03-15T08:00:00Z");
  }

  @Test
  void transitionsInOrder() {
    List<SessionTransition> day = CAL.transitionsBetween(Instant.parse("2026-10-09T00:00:00Z"),
        Instant.parse("2026-10-10T00:00:00Z"));
    assertThat(day).extracting(SessionTransition::type).containsExactly(TransitionType.PRE_OPEN,
        TransitionType.RTH_OPEN, TransitionType.RTH_CLOSE, TransitionType.POST_CLOSE);
    assertThat(CAL.nextTransitionAfter(Instant.parse("2026-10-09T20:00:00Z")).type())
        .isEqualTo(TransitionType.POST_CLOSE);
    assertThat(CAL.nextTransitionAfter(Instant.parse("2026-12-24T22:00:00Z")).at())
        .hasToString("2026-12-28T09:00:00Z");                                  // Christmas, then the weekend
  }

  @Test
  void tradingDayNavigationAndSettlement() {
    assertThat(CAL.nextTradingDay(LocalDate.of(2026, 12, 24))).isEqualTo(LocalDate.of(2026, 12, 28));
    assertThat(CAL.previousTradingDay(LocalDate.of(2026, 10, 12))).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(CAL.tradingDayOnOrBefore(LocalDate.of(2026, 10, 11))).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(CAL.settlementDate(LocalDate.of(2026, 10, 9))).isEqualTo(LocalDate.of(2026, 10, 13));
    assertThatThrownBy(() -> CAL.settlementDate(LocalDate.of(2026, 10, 10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void pnlDayStartsAtPreOpen() {
    assertThat(CAL.currentPnlDay(Instant.parse("2026-10-12T07:59:00Z"))).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(CAL.currentPnlDay(Instant.parse("2026-10-12T08:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 12));
    assertThat(CAL.currentPnlDay(Instant.parse("2026-10-11T12:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(CAL.tradeDateFor(Instant.parse("2026-10-10T03:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 9));
  }

  @Test
  void generatorFallbackOutsideTheBundledRange() {
    assertThat(CAL.isTradingDay(LocalDate.of(2029, 1, 2))).isTrue();
    assertThat(CAL.isTradingDay(LocalDate.of(2029, 1, 1))).isFalse();
    assertThat(CAL.day(LocalDate.of(2029, 1, 2)).orElseThrow().source()).isEqualTo("generated");
  }

  @Test
  void alwaysOpenCalendar() {
    MarketCalendar cal = new AlwaysOpenCalendar();
    Instant sunday = Instant.parse("2026-10-11T05:00:00Z");                    // Sun 01:00 ET
    SessionState s = cal.stateAt(sunday);
    assertThat(s.type()).isEqualTo(SessionType.REGULAR);
    assertThat(s.nextType()).isEqualTo(SessionType.CLOSED);
    assertThat(cal.settlementDate(LocalDate.of(2026, 10, 9))).isEqualTo(LocalDate.of(2026, 10, 12));
    assertThat(cal.transitionsBetween(Instant.parse("2026-10-10T04:00:00Z"), Instant.parse("2026-10-11T04:00:00Z")))
        .hasSize(4);
    assertThat(cal.currentPnlDay(sunday)).isEqualTo(LocalDate.of(2026, 10, 11));
  }
}
