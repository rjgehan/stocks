package com.stocks.calendar;

import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.contract.marketdata.model.TradingDayData;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code CALENDAR_SYNC} (§9.1 source 2, §25): Sundays at 03:05 ET and at boot, the provider calendar for
 * {@code [today − 30 d, today + 2 y]} replaces the stored rows of that window ({@code source=ALPACA}, with Alpaca's
 * {@code settlement_date}); dates the provider omits are closed. A provider answer that is empty or implausibly short
 * (fewer than 200 days for the window) changes nothing. Without a provider (SIM) the job does nothing.
 */
public class CalendarSyncJob implements ScheduledJob {

  private static final Logger log = LoggerFactory.getLogger(CalendarSyncJob.class);
  static final LocalTime AT = LocalTime.of(3, 5);
  static final int MIN_PLAUSIBLE_DAYS = 200;

  private final CalendarRepository repository;
  private final DbMarketCalendar calendar;
  private final BiFunction<LocalDate, LocalDate, List<TradingDayData>> source;
  private final TimeSource time;
  private final Runnable onChange;

  /** {@code sourceOrNull}: the provider calendar ({@code from}, {@code to} inclusive); null = no provider. */
  public CalendarSyncJob(CalendarRepository repository, DbMarketCalendar calendar,
                         BiFunction<LocalDate, LocalDate, List<TradingDayData>> sourceOrNull, TimeSource time,
                         Runnable onChange) {
    this.repository = repository;
    this.calendar = calendar;
    this.source = sourceOrNull;
    this.time = time;
    this.onChange = onChange == null ? () -> { } : onChange;
  }

  @Override
  public String name() {
    return "CALENDAR_SYNC";
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    List<JobOccurrence> out = new ArrayList<>();
    if (!toInclusive.isAfter(fromExclusive)) {
      return out;
    }
    for (LocalDate d = Et.date(fromExclusive); !d.isAfter(Et.date(toInclusive)); d = d.plusDays(1)) {
      Instant at = Et.at(d, AT);
      if (d.getDayOfWeek() == DayOfWeek.SUNDAY && at.isAfter(fromExclusive) && !at.isAfter(toInclusive)) {
        out.add(new JobOccurrence(at, d.toString()));
      }
    }
    return out;
  }

  @Override
  public void run(String runKey) {
    sync();
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.LATEST_ONLY;
  }

  /** Pulls and stores the provider calendar; returns the number of trading days stored (0 = nothing changed). */
  public int sync() {
    if (source == null || calendar == null) {
      return 0;
    }
    LocalDate today = Et.date(time.now());
    LocalDate from = today.minusDays(30);
    LocalDate to = today.plusYears(2);
    List<TradingDayData> rows;
    try {
      rows = source.apply(from, to);
    } catch (RuntimeException e) {
      log.warn("Calendar sync: provider calendar unavailable: {}", e.getMessage());
      return 0;
    }
    List<TradingDay> days = new ArrayList<>();
    for (TradingDayData r : rows) {
      try {
        if (r.date() != null && !r.date().isBefore(from) && !r.date().isAfter(to)) {
          days.add(toTradingDay(r));
        }
      } catch (RuntimeException e) {
        log.warn("Calendar sync: skipping {}: {}", r, e.getMessage());
      }
    }
    if (days.size() < MIN_PLAUSIBLE_DAYS) {
      log.warn("Calendar sync: provider returned {} days for {}..{}; keeping the stored calendar", days.size(), from,
          to);
      return 0;
    }
    LocalDate first = days.stream().map(TradingDay::tradeDate).min(LocalDate::compareTo).orElseThrow();
    LocalDate last = days.stream().map(TradingDay::tradeDate).max(LocalDate::compareTo).orElseThrow();
    Instant now = time.now();
    repository.upsert(days, now);
    Set<LocalDate> keep = new TreeSet<>();
    days.forEach(d -> keep.add(d.tradeDate()));
    int removed = repository.deleteNotIn(first, last, keep);
    calendar.reload();
    onChange.run();
    log.info("Calendar sync: {} trading days {}..{} from the provider ({} closed days removed)", days.size(), first,
        last, removed);
    return days.size();
  }

  /** A provider row as a {@code TradingDay}: missing extended-session times default to 04:00 and 20:00 (17:00 early). */
  static TradingDay toTradingDay(TradingDayData r) {
    LocalTime open = r.open();
    LocalTime close = r.close();
    boolean early = close.isBefore(NyseCalendarGenerator.RTH_CLOSE);
    LocalTime pre = r.preOpen() != null ? r.preOpen() : NyseCalendarGenerator.PRE_OPEN;
    LocalTime post = r.postClose() != null ? r.postClose()
        : early ? NyseCalendarGenerator.EARLY_POST_CLOSE : NyseCalendarGenerator.POST_CLOSE;
    LocalDate settle = r.settlementDate() != null ? r.settlementDate()
        : NyseCalendarGenerator.settlementDate(r.date());
    return TradingDays.of(r.date(), pre, open, close, post, settle, early, TradingDays.ALPACA);
  }
}
