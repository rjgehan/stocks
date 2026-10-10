package com.stocks.marketdata.core;

import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.contract.marketdata.DailyMarkService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code DAILY_MARK} (§15.5, §25) on every trading day: 20 minutes after the regular close (16:20, or 13:20 on early
 * closes) the official closes ({@code run_key = <date>:OFFICIAL}) for held, watched and universe symbols plus SPY; 20
 * minutes after the post close (20:20 / 17:20) the day-end marks ({@code run_key = <date>}) for held and watched
 * symbols plus the pinned ones. WS4's {@code EodSnapshotJob} calls the same idempotent {@link DailyMarkService}.
 */
public class DailyMarkJob implements ScheduledJob {

  static final Duration DELAY = Duration.ofMinutes(20);
  static final String OFFICIAL = ":OFFICIAL";

  private final DailyMarkService marks;
  private final Supplier<Set<String>> officialSymbols;
  private final Supplier<Set<String>> dayEndSymbols;

  public DailyMarkJob(DailyMarkService marks, Supplier<Set<String>> officialSymbols,
                      Supplier<Set<String>> dayEndSymbols) {
    this.marks = marks;
    this.officialSymbols = officialSymbols;
    this.dayEndSymbols = dayEndSymbols;
  }

  @Override
  public String name() {
    return "DAILY_MARK";
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    List<JobOccurrence> out = new ArrayList<>(EtSchedule.tradingDays(fromExclusive, toInclusive, cal,
        (td, d) -> td.rthCloseAt(), DELAY, d -> d + OFFICIAL));
    out.addAll(EtSchedule.tradingDays(fromExclusive, toInclusive, cal, (TradingDay td, LocalDate d) -> td.postCloseAt(),
        DELAY, LocalDate::toString));
    out.sort((a, b) -> a.at().compareTo(b.at()));
    return out;
  }

  @Override
  public void run(String runKey) {
    if (runKey.endsWith(OFFICIAL)) {
      LocalDate d = LocalDate.parse(runKey.substring(0, runKey.length() - OFFICIAL.length()));
      marks.ensureOfficialCloses(d, officialSymbols.get());
    } else {
      LocalDate d = LocalDate.parse(runKey);
      marks.ensureOfficialCloses(d, dayEndSymbols.get());
      marks.ensureDayEndMarks(d, dayEndSymbols.get());
    }
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.ALL_MISSED;
  }
}
