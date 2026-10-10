package com.stocks.trading.corpactions;

import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.trading.engine.EngineCalls;
import com.stocks.trading.engine.EngineCommand;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code PAY_DIVIDENDS} (§14, §25): {@code PayDividendsCmd} at 06:00 ET on trading days (run key = date, catch-up
 * ALL_MISSED). A payable date on a weekend or holiday is paid on the next trading day's run.
 */
@Component
public class PayDividendsJob implements ScheduledJob {

  public static final String NAME = "PAY_DIVIDENDS";
  static final LocalTime AT = LocalTime.of(6, 0);

  private final EngineCalls engine;

  public PayDividendsJob(EngineCalls engine) {
    this.engine = engine;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    List<JobOccurrence> out = new ArrayList<>();
    for (LocalDate d = Et.date(fromExclusive); !d.isAfter(Et.date(toInclusive)); d = d.plusDays(1)) {
      Instant at = Et.at(d, AT);
      if (at.isAfter(fromExclusive) && !at.isAfter(toInclusive) && cal.isTradingDay(d)) {
        out.add(new JobOccurrence(at, d.toString()));
      }
    }
    return out;
  }

  @Override
  public void run(String runKey) {
    engine.call(new EngineCommand.PayDividendsCmd(LocalDate.parse(runKey)));
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.ALL_MISSED;
  }
}
