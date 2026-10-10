package com.stocks.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** §9.2 reference dates and the required settlement golden table. */
class NyseCalendarGeneratorTest {

  @ParameterizedTest(name = "{0} closed ({1})")
  @CsvSource({
      "2026-01-01, New Year's Day", "2026-01-19, Martin Luther King Jr. Day", "2026-02-16, Washington's Birthday",
      "2026-04-03, Good Friday", "2026-05-25, Memorial Day", "2026-06-19, Juneteenth",
      "2026-07-03, Independence Day", "2026-09-07, Labor Day", "2026-11-26, Thanksgiving Day",
      "2026-12-25, Christmas Day",
      "2027-01-01, New Year's Day", "2027-01-18, Martin Luther King Jr. Day", "2027-02-15, Washington's Birthday",
      "2027-03-26, Good Friday", "2027-05-31, Memorial Day", "2027-06-18, Juneteenth",
      "2027-07-05, Independence Day", "2027-09-06, Labor Day", "2027-11-25, Thanksgiving Day",
      "2027-12-24, Christmas Day",
      "2018-12-05, National Day of Mourning (George H. W. Bush)",
      "2025-01-09, National Day of Mourning (Jimmy Carter)"})
  void holidays(LocalDate date, String name) {
    assertThat(NyseCalendarGenerator.isTradingDay(date)).isFalse();
    assertThat(NyseCalendarGenerator.holidayName(date)).contains(name);
    assertThat(NyseCalendarGenerator.row(date)).isEmpty();
  }

  @ParameterizedTest(name = "{0} early close")
  @CsvSource({"2026-11-27", "2026-12-24", "2027-11-26", "2025-07-03", "2028-07-03"})
  void earlyCloses(LocalDate date) {
    NyseCalendarGenerator.Row r = NyseCalendarGenerator.row(date).orElseThrow();
    assertThat(r.earlyClose()).isTrue();
    assertThat(r.rthClose()).isEqualTo(LocalTime.of(13, 0));
    assertThat(r.postClose()).isEqualTo(LocalTime.of(17, 0));
    assertThat(r.preOpen()).isEqualTo(LocalTime.of(4, 0));
    assertThat(r.rthOpen()).isEqualTo(LocalTime.of(9, 30));
  }

  @ParameterizedTest(name = "{0} settles {1}")
  @CsvSource({
      "2026-10-09, 2026-10-13",   // Columbus Day in between
      "2026-10-12, 2026-10-13",
      "2026-11-10, 2026-11-12",   // Veterans Day
      "2026-11-25, 2026-11-27",
      "2026-12-31, 2027-01-04",
      "2027-10-08, 2027-10-12",   // Columbus Day 2027
      "2027-12-31, 2028-01-03"})
  void settlementGolden(LocalDate trade, LocalDate settles) {
    assertThat(NyseCalendarGenerator.settlementDate(trade)).isEqualTo(settles);
    assertThat(NyseCalendarGenerator.row(trade).orElseThrow().settlementDate()).isEqualTo(settles);
  }

  @Test
  void bankOnlyHolidaysAreTradingDaysButNotSettlementDays() {
    for (LocalDate d : List.of(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 11, 11), LocalDate.of(2027, 10, 11),
        LocalDate.of(2027, 11, 11))) {
      assertThat(NyseCalendarGenerator.isTradingDay(d)).as(d.toString()).isTrue();
      assertThat(NyseCalendarGenerator.isFedHoliday(d)).as(d.toString()).isTrue();
    }
  }

  @Test
  void newYearOnSaturday2028IsNotObserved() {
    assertThat(NyseCalendarGenerator.isTradingDay(LocalDate.of(2027, 12, 31))).isTrue();
    assertThat(NyseCalendarGenerator.holidayName(LocalDate.of(2027, 12, 31))).isEmpty();
    assertThat(NyseCalendarGenerator.isTradingDay(LocalDate.of(2028, 1, 3))).isTrue();
  }

  @Test
  void settlementCyclesFollowTheSecEras() {
    assertThat(NyseCalendarGenerator.settlementDate(LocalDate.of(2017, 9, 1))).isEqualTo(LocalDate.of(2017, 9, 7));
    assertThat(NyseCalendarGenerator.settlementDate(LocalDate.of(2024, 5, 24))).isEqualTo(LocalDate.of(2024, 5, 29));
    assertThat(NyseCalendarGenerator.settlementDate(LocalDate.of(2024, 5, 28))).isEqualTo(LocalDate.of(2024, 5, 29));
  }

  @Test
  void easter() {
    assertThat(NyseCalendarGenerator.easter(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
    assertThat(NyseCalendarGenerator.easter(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
    assertThat(NyseCalendarGenerator.easter(2028)).isEqualTo(LocalDate.of(2028, 4, 16));
  }
}
