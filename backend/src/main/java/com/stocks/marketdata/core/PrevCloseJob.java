package com.stocks.marketdata.core;

import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.contract.marketdata.DailyMarkService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code PREV_CLOSE} (§15.5, §25): 04:01 ET on trading days. Makes sure the official closes of the previous trading day
 * are stored and loads them into the quote book (the {@code prevClose} of every quote), then runs the
 * {@link SplitGuard}. {@code run_key} = date.
 */
public class PrevCloseJob implements ScheduledJob {

  static final LocalTime AT = LocalTime.of(4, 1);

  private final DailyMarkService marks;
  private final DailyMarkRepository repository;
  private final MarketFeed feed;
  private final SplitGuard guard;
  private final Supplier<Set<String>> symbols;
  private final MarketCalendar calendar;

  public PrevCloseJob(DailyMarkService marks, DailyMarkRepository repository, MarketFeed feed, SplitGuard guard,
                      Supplier<Set<String>> symbols, MarketCalendar calendar) {
    this.marks = marks;
    this.repository = repository;
    this.feed = feed;
    this.guard = guard;
    this.symbols = symbols;
    this.calendar = calendar;
  }

  @Override
  public String name() {
    return "PREV_CLOSE";
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    return EtSchedule.daily(fromExclusive, toInclusive, AT, cal::isTradingDay);
  }

  @Override
  public void run(String runKey) {
    LocalDate prev = calendar.previousTradingDay(LocalDate.parse(runKey));
    Set<String> all = symbols.get();
    marks.ensureOfficialCloses(prev, all);
    Map<String, BigDecimal> closes = repository.officialCloses(prev, all);
    closes.forEach((s, c) -> feed.officialClose(s, prev, c));
    guard.run();
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.LATEST_ONLY;
  }
}
