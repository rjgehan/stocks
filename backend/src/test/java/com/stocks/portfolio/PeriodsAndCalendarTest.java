package com.stocks.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.identity.SettingsService;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §16.3 P&amp;L days and period anchors, on a real-ish calendar and on the Day-0 fallback. */
class PeriodsAndCalendarTest {

  static final LocalDate FRI = LocalDate.of(2026, 10, 9);
  static final LocalDate MON_COLUMBUS = LocalDate.of(2026, 10, 12);

  final PnlCalendar real = new PnlCalendar(new FakeCalendar());
  final PnlCalendar fallback = new PnlCalendar(NotImplementedStub.of(MarketCalendar.class, "WS2"));
  final LeagueSettings defaults = new LeagueSettings(NotImplementedStub.of(SettingsService.class, "WS1"));

  @Test
  void pnlDayStartsAtPreOpen() {
    for (PnlCalendar c : List.of(real, fallback)) {
      assertThat(c.currentPnlDay(Instant.parse("2026-10-09T14:00:00Z"))).isEqualTo(FRI);       // Fri 10:00 ET
      assertThat(c.currentPnlDay(Instant.parse("2026-10-10T15:00:00Z"))).isEqualTo(FRI);       // Saturday
      assertThat(c.currentPnlDay(Instant.parse("2026-10-12T07:59:59Z"))).isEqualTo(FRI);       // Mon 03:59 ET
      assertThat(c.currentPnlDay(Instant.parse("2026-10-12T08:00:00Z"))).isEqualTo(MON_COLUMBUS);  // 04:00 ET
      assertThat(c.cutoff(FRI)).isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));               // 20:00 ET
      assertThat(c.preOpen(FRI)).isEqualTo(Instant.parse("2026-10-09T08:00:00Z"));
      assertThat(c.previousTradingDay(MON_COLUMBUS)).isEqualTo(FRI);
      assertThat(c.nextTradingDay(FRI)).isEqualTo(MON_COLUMBUS);
      assertThat(c.tradingDayOnOrBefore(LocalDate.of(2026, 10, 11))).isEqualTo(FRI);
      assertThat(c.tradingDaysBetween(LocalDate.of(2026, 10, 8), MON_COLUMBUS)).containsExactly(FRI, MON_COLUMBUS);
      assertThat(c.tradingDaysBetween(FRI, FRI)).isEmpty();
    }
    assertThat(real.usingFallback()).isFalse();
    assertThat(fallback.usingFallback()).isTrue();
  }

  /** §29: while crypto is on, every calendar day is a P&amp;L day (weekends and holidays end at 20:00 ET). */
  @Test
  void withCryptoEveryCalendarDayIsAPnlDay() {
    PnlCalendar c = new PnlCalendar(new FakeCalendar(), () -> true);
    LocalDate sat = LocalDate.of(2026, 10, 10);
    LocalDate sun = LocalDate.of(2026, 10, 11);
    assertThat(c.everyDay()).isTrue();
    assertThat(c.currentPnlDay(Instant.parse("2026-10-10T15:00:00Z"))).isEqualTo(sat);           // Sat 11:00 ET
    assertThat(c.currentPnlDay(Instant.parse("2026-10-10T07:59:59Z"))).isEqualTo(FRI);           // Sat 03:59 ET
    assertThat(c.cutoff(sat)).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));                   // 20:00 ET
    assertThat(c.previousTradingDay(MON_COLUMBUS)).isEqualTo(sun);
    assertThat(c.tradingDaysBetween(FRI, MON_COLUMBUS)).containsExactly(sat, sun, MON_COLUMBUS);
    assertThat(c.isTradingDay(LocalDate.of(2026, 11, 26))).isTrue();                             // Thanksgiving
    assertThat(c.cutoff(LocalDate.of(2026, 11, 27))).isEqualTo(Instant.parse("2026-11-27T22:00:00Z"));  // 17:00
    PeriodResolver p = new PeriodResolver(c, defaults);
    assertThat(p.anchorDate(LeaderboardPeriod.TODAY, MON_COLUMBUS)).isEqualTo(sun);
    assertThat(p.anchorDate(LeaderboardPeriod.WEEK, MON_COLUMBUS)).isEqualTo(sun);
    assertThat(new PnlCalendar(new FakeCalendar(), () -> false).isTradingDay(sat)).isFalse();
  }

  @Test
  void theRealCalendarKnowsHolidaysAndEarlyCloses() {
    assertThat(real.isTradingDay(LocalDate.of(2026, 11, 26))).isFalse();                       // Thanksgiving
    assertThat(fallback.isTradingDay(LocalDate.of(2026, 11, 26))).isTrue();                    // weekdays only
    assertThat(real.cutoff(LocalDate.of(2026, 11, 27))).isEqualTo(Instant.parse("2026-11-27T22:00:00Z"));  // 17:00
    assertThat(real.previousTradingDay(LocalDate.of(2026, 11, 27))).isEqualTo(LocalDate.of(2026, 11, 25));
  }

  @Test
  void otherCalendarErrorsAreNotSwallowed() {
    MarketCalendar broken = (MarketCalendar) Proxy.newProxyInstance(MarketCalendar.class.getClassLoader(),
        new Class<?>[] {MarketCalendar.class}, (p, m, a) -> {
          throw ApiException.of(ErrorCode.INTERNAL_ERROR, "boom");
        });
    assertThatThrownBy(() -> new PnlCalendar(broken).isTradingDay(FRI))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
  }

  @Test
  void leaderboardAnchors() {
    PeriodResolver p = new PeriodResolver(real, defaults);
    assertThat(p.anchorDate(LeaderboardPeriod.TODAY, FRI)).isEqualTo(LocalDate.of(2026, 10, 8));
    assertThat(p.anchorDate(LeaderboardPeriod.WEEK, FRI)).isEqualTo(LocalDate.of(2026, 10, 2));      // prior Fri
    assertThat(p.anchorDate(LeaderboardPeriod.WEEK, MON_COLUMBUS)).isEqualTo(FRI);
    assertThat(p.anchorDate(LeaderboardPeriod.MONTH, FRI)).isEqualTo(LocalDate.of(2026, 9, 30));
    assertThat(p.anchorDate(LeaderboardPeriod.YTD, FRI)).isEqualTo(LocalDate.of(2025, 12, 31));
    assertThat(p.anchorDate(LeaderboardPeriod.ALL, FRI)).isNull();
    // a Monday holiday: the week's first trading day is Tuesday, so the anchor is the Friday before
    assertThat(p.anchorDate(LeaderboardPeriod.WEEK, LocalDate.of(2026, 9, 10))).isEqualTo(LocalDate.of(2026, 9, 4));
    // season start 2026-10-12 (§23.4): the last trading day before it
    assertThat(p.anchorDate(LeaderboardPeriod.SEASON, LocalDate.of(2026, 10, 20))).isEqualTo(FRI);
    // a season that has not started yet behaves like TODAY
    assertThat(p.anchorDate(LeaderboardPeriod.SEASON, FRI)).isEqualTo(LocalDate.of(2026, 10, 8));
    PeriodResolver.Window w = p.window(LeaderboardPeriod.WEEK, FRI);
    assertThat(w.anchorAt()).isEqualTo(Instant.parse("2026-10-03T00:00:00Z"));
    assertThat(w.sinceOpening()).isFalse();
    assertThat(p.window(LeaderboardPeriod.ALL, FRI).sinceOpening()).isTrue();
  }

  @Test
  void historyAnchors() {
    PeriodResolver p = new PeriodResolver(real, defaults);
    assertThat(p.anchorDate(HistoryRange.D1, FRI)).isEqualTo(LocalDate.of(2026, 10, 8));
    assertThat(p.anchorDate(HistoryRange.W1, FRI)).isEqualTo(LocalDate.of(2026, 10, 2));
    assertThat(p.anchorDate(HistoryRange.M1, FRI)).isEqualTo(LocalDate.of(2026, 9, 9));
    assertThat(p.anchorDate(HistoryRange.M3, FRI)).isEqualTo(LocalDate.of(2026, 7, 9));
    assertThat(p.anchorDate(HistoryRange.YTD, FRI)).isEqualTo(LocalDate.of(2025, 12, 31));
    assertThat(p.anchorDate(HistoryRange.Y1, FRI)).isEqualTo(LocalDate.of(2025, 10, 9));
    assertThat(p.anchorDate(HistoryRange.ALL, FRI)).isNull();
    assertThat(p.window(HistoryRange.ALL, FRI).anchorAt()).isNull();
  }

  @Test
  void settingsFallBackToTheSection23Defaults() {
    assertThat(defaults.seasonStart()).isEqualTo(LocalDate.of(2026, 10, 12));
    assertThat(defaults.minFills()).isEqualTo(5);
    assertThat(defaults.minSymbols()).isEqualTo(2);
    assertThat(defaults.tradeFeedDelay()).isZero();                 // public mode (§16.7): no delay by default
    assertThat(defaults.showDepositsInFeed()).isTrue();
    assertThat(defaults.humanKindExcludesApiTraders()).isFalse();
    SettingsService broken = (SettingsService) Proxy.newProxyInstance(SettingsService.class.getClassLoader(),
        new Class<?>[] {SettingsService.class}, (px, m, a) -> {
          throw ApiException.of(ErrorCode.SETTING_INVALID, "bad");
        });
    assertThatThrownBy(() -> new LeagueSettings(broken).minFills()).isInstanceOf(ApiException.class);
  }
}
