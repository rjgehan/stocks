package com.stocks.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rule-based NYSE (XNYS) calendar (§9.1, §9.2): full-day holidays, the 13:00 early closes, the two national days of
 * mourning of the period, and the settlement date (the next weekday that is an NYSE trading day <b>and</b> not a
 * Federal Reserve holiday, so Columbus Day and Veterans Day are trading days but not settlement days).
 *
 * <p>It produces {@code resources/calendar/xnys-2016-2028.csv} ({@code BundledCalendarCsvTest} regenerates and
 * compares it), and it is the last-resort source for dates outside the bundled range. Settlement cycles follow the SEC:
 * T+3 before 2017-09-05, T+2 until 2024-05-27, T+1 from 2024-05-28.
 */
public final class NyseCalendarGenerator {

  public static final LocalTime PRE_OPEN = LocalTime.of(4, 0);
  public static final LocalTime RTH_OPEN = LocalTime.of(9, 30);
  public static final LocalTime RTH_CLOSE = LocalTime.of(16, 0);
  public static final LocalTime POST_CLOSE = LocalTime.of(20, 0);
  public static final LocalTime EARLY_RTH_CLOSE = LocalTime.of(13, 0);
  public static final LocalTime EARLY_POST_CLOSE = LocalTime.of(17, 0);

  static final LocalDate T2_FROM = LocalDate.of(2017, 9, 5);
  static final LocalDate T1_FROM = LocalDate.of(2024, 5, 28);

  /** Unscheduled full-day closures inside the generated range. */
  private static final Map<LocalDate, String> SPECIAL_CLOSURES = Map.of(
      LocalDate.of(2018, 12, 5), "National Day of Mourning (George H. W. Bush)",
      LocalDate.of(2025, 1, 9), "National Day of Mourning (Jimmy Carter)");

  /** One generated row (ET wall-clock times). */
  public record Row(LocalDate date, LocalTime preOpen, LocalTime rthOpen, LocalTime rthClose, LocalTime postClose,
                    LocalDate settlementDate, boolean earlyClose) {

    /** The bundled CSV line: {@code 2026-10-09,04:00,09:30,16:00,20:00,2026-10-13,false}. */
    public String csv() {
      return date + "," + preOpen + "," + rthOpen + "," + rthClose + "," + postClose + "," + settlementDate + ","
          + earlyClose;
    }
  }

  private NyseCalendarGenerator() {}

  /** Every trading day in {@code [from, to]}, in order. */
  public static List<Row> generate(LocalDate from, LocalDate to) {
    List<Row> out = new ArrayList<>();
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      row(d).ifPresent(out::add);
    }
    return out;
  }

  /** The row for {@code date}, or empty when the NYSE is closed. */
  public static Optional<Row> row(LocalDate date) {
    if (!isTradingDay(date)) {
      return Optional.empty();
    }
    boolean early = isEarlyClose(date);
    return Optional.of(new Row(date, PRE_OPEN, RTH_OPEN, early ? EARLY_RTH_CLOSE : RTH_CLOSE,
        early ? EARLY_POST_CLOSE : POST_CLOSE, settlementDate(date), early));
  }

  public static boolean isTradingDay(LocalDate date) {
    return !isWeekend(date) && holidayName(date).isEmpty();
  }

  /** The NYSE holiday (or special closure) on a weekday, by name. */
  public static Optional<String> holidayName(LocalDate date) {
    if (isWeekend(date)) {
      return Optional.empty();
    }
    String special = SPECIAL_CLOSURES.get(date);
    if (special != null) {
      return Optional.of(special);
    }
    return Optional.ofNullable(nyseHolidays(date.getYear()).get(date));
  }

  /** 13:00 close: July 3 (Mon–Thu), the day after Thanksgiving, Christmas Eve (Mon–Thu). */
  public static boolean isEarlyClose(LocalDate date) {
    if (!isTradingDay(date)) {
      return false;
    }
    int y = date.getYear();
    if (date.equals(LocalDate.of(y, 7, 3))) {
      return true;                                     // trading day ⇒ Mon–Fri; Fri would be the observed 4th
    }
    if (date.equals(thanksgiving(y).plusDays(1))) {
      return true;
    }
    return date.equals(LocalDate.of(y, 12, 24));       // a trading Dec 24 is Mon–Thu (Fri = observed Christmas)
  }

  /** Settlement date of a trade on {@code tradeDate} (T+3 / T+2 / T+1 by era). */
  public static LocalDate settlementDate(LocalDate tradeDate) {
    int cycle = tradeDate.isBefore(T2_FROM) ? 3 : tradeDate.isBefore(T1_FROM) ? 2 : 1;
    LocalDate d = tradeDate;
    for (int i = 0; i < cycle; i++) {
      d = nextSettlementDay(d);
    }
    return d;
  }

  private static LocalDate nextSettlementDay(LocalDate date) {
    LocalDate d = date.plusDays(1);
    while (!isTradingDay(d) || isFedHoliday(d)) {
      d = d.plusDays(1);
    }
    return d;
  }

  /** Federal Reserve holidays (no settlement). Saturday holidays are not observed; Sunday ones move to Monday. */
  public static boolean isFedHoliday(LocalDate date) {
    if (isWeekend(date)) {
      return true;
    }
    int y = date.getYear();
    List<LocalDate> fixed = new ArrayList<>(List.of(
        LocalDate.of(y, 1, 1), LocalDate.of(y, 7, 4), LocalDate.of(y, 11, 11), LocalDate.of(y, 12, 25)));
    if (y >= 2021) {
      fixed.add(LocalDate.of(y, 6, 19));
    }
    for (LocalDate h : fixed) {
      LocalDate observed = h.getDayOfWeek() == DayOfWeek.SUNDAY ? h.plusDays(1) : h;
      if (date.equals(observed)) {
        return true;
      }
    }
    return date.equals(nthWeekday(y, Month.JANUARY, DayOfWeek.MONDAY, 3))
        || date.equals(nthWeekday(y, Month.FEBRUARY, DayOfWeek.MONDAY, 3))
        || date.equals(lastWeekday(y, Month.MAY, DayOfWeek.MONDAY))
        || date.equals(nthWeekday(y, Month.SEPTEMBER, DayOfWeek.MONDAY, 1))
        || date.equals(nthWeekday(y, Month.OCTOBER, DayOfWeek.MONDAY, 2))
        || date.equals(thanksgiving(y));
  }

  /** NYSE full-day holidays of a year, by observed date. */
  static Map<LocalDate, String> nyseHolidays(int y) {
    Map<LocalDate, String> h = new LinkedHashMap<>();
    LocalDate newYear = LocalDate.of(y, 1, 1);
    if (newYear.getDayOfWeek() == DayOfWeek.SUNDAY) {
      h.put(newYear.plusDays(1), "New Year's Day");
    } else if (newYear.getDayOfWeek() != DayOfWeek.SATURDAY) {  // Saturday: not observed (NYSE rule)
      h.put(newYear, "New Year's Day");
    }
    h.put(nthWeekday(y, Month.JANUARY, DayOfWeek.MONDAY, 3), "Martin Luther King Jr. Day");
    h.put(nthWeekday(y, Month.FEBRUARY, DayOfWeek.MONDAY, 3), "Washington's Birthday");
    h.put(easter(y).minusDays(2), "Good Friday");
    h.put(lastWeekday(y, Month.MAY, DayOfWeek.MONDAY), "Memorial Day");
    if (y >= 2022) {
      h.put(observed(LocalDate.of(y, 6, 19)), "Juneteenth");
    }
    h.put(observed(LocalDate.of(y, 7, 4)), "Independence Day");
    h.put(nthWeekday(y, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labor Day");
    h.put(thanksgiving(y), "Thanksgiving Day");
    LocalDate christmas = LocalDate.of(y, 12, 25);
    h.put(observed(christmas), "Christmas Day");
    return h;
  }

  /** Saturday → Friday, Sunday → Monday. */
  private static LocalDate observed(LocalDate d) {
    return switch (d.getDayOfWeek()) {
      case SATURDAY -> d.minusDays(1);
      case SUNDAY -> d.plusDays(1);
      default -> d;
    };
  }

  static LocalDate thanksgiving(int y) {
    return nthWeekday(y, Month.NOVEMBER, DayOfWeek.THURSDAY, 4);
  }

  private static LocalDate nthWeekday(int y, Month m, DayOfWeek dow, int n) {
    return LocalDate.of(y, m, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
  }

  private static LocalDate lastWeekday(int y, Month m, DayOfWeek dow) {
    return LocalDate.of(y, m, 1).with(TemporalAdjusters.lastInMonth(dow));
  }

  /** Gregorian Easter Sunday (anonymous Gregorian algorithm). */
  static LocalDate easter(int y) {
    int a = y % 19;
    int b = y / 100;
    int c = y % 100;
    int d = b / 4;
    int e = b % 4;
    int f = (b + 8) / 25;
    int g = (b - f + 1) / 3;
    int h = (19 * a + b - d - g + 15) % 30;
    int i = c / 4;
    int k = c % 4;
    int l = (32 + 2 * e + 2 * i - h - k) % 7;
    int m = (a + 11 * h + 22 * l) / 451;
    int month = (h + l - 7 * m + 114) / 31;
    int day = ((h + l - 7 * m + 114) % 31) + 1;
    return LocalDate.of(y, month, day);
  }

  private static boolean isWeekend(LocalDate d) {
    return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
  }
}
