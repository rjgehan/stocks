package com.stocks.marketdata.core;

import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.jobs.JobOccurrence;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/** ET schedules for the market-data jobs (§25): occurrences in {@code (from, to]}, expressed through the calendar. */
public final class EtSchedule {

  private EtSchedule() {}

  /** Every ET date matching {@code days} at {@code time}; run key = the date. */
  public static List<JobOccurrence> daily(Instant fromExclusive, Instant toInclusive, LocalTime time,
                                          Predicate<LocalDate> days) {
    return daily(fromExclusive, toInclusive, d -> days.test(d) ? Et.at(d, time) : null, LocalDate::toString);
  }

  /**
   * One occurrence per ET date: {@code at(d)} (null = none that day) with {@code key(d)}. Dates from the day before
   * {@code from} through {@code to} are considered.
   */
  public static List<JobOccurrence> daily(Instant fromExclusive, Instant toInclusive, Function<LocalDate, Instant> at,
                                          Function<LocalDate, String> key) {
    List<JobOccurrence> out = new ArrayList<>();
    if (!toInclusive.isAfter(fromExclusive)) {
      return out;
    }
    LocalDate last = Et.date(toInclusive);
    for (LocalDate d = Et.date(fromExclusive).minusDays(1); !d.isAfter(last); d = d.plusDays(1)) {
      Instant t = at.apply(d);
      if (t != null && t.isAfter(fromExclusive) && !t.isAfter(toInclusive)) {
        out.add(new JobOccurrence(t, key.apply(d)));
      }
    }
    out.sort((a, b) -> a.at().compareTo(b.at()));
    return out;
  }

  /** On each trading day: {@code offset} after the instant {@code pick} chooses from the day. */
  public static List<JobOccurrence> tradingDays(Instant fromExclusive, Instant toInclusive, MarketCalendar calendar,
                                                BiFunction<TradingDay, LocalDate, Instant> pick, Duration offset,
                                                Function<LocalDate, String> key) {
    return daily(fromExclusive, toInclusive,
        d -> calendar.day(d).map(td -> pick.apply(td, d)).map(i -> i.plus(offset)).orElse(null), key);
  }
}
