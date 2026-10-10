package com.stocks.trading.session;

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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The engine's daily jobs (§25): {@code DAILY_MAINTENANCE} at 03:30 ET (GFV restriction lifting, run key = date) and
 * {@code CHECK_INVARIANTS} at 04:05 ET and then every hour (run key {@code date:HH}). Both catch up LATEST_ONLY.
 */
@Configuration(proxyBeanMethods = false)
public class DailyTradingJobs {

  public static final String DAILY_MAINTENANCE = "DAILY_MAINTENANCE";
  public static final String CHECK_INVARIANTS = "CHECK_INVARIANTS";

  @Bean
  ScheduledJob dailyMaintenanceJob(EngineCalls engine) {
    return new EtClockJob(DAILY_MAINTENANCE, List.of(LocalTime.of(3, 30)), false,
        runKey -> engine.call(new EngineCommand.DailyMaintenanceCmd(LocalDate.parse(runKey))));
  }

  @Bean
  ScheduledJob invariantCheckJob(EngineCalls engine) {
    List<LocalTime> times = new ArrayList<>();
    for (int h = 4; h < 24; h++) {
      times.add(LocalTime.of(h, 5));
    }
    return new EtClockJob(CHECK_INVARIANTS, times, true, runKey -> engine.call(new EngineCommand.CheckInvariantsCmd()));
  }

  /** A job at fixed ET wall-clock times every day. */
  static final class EtClockJob implements ScheduledJob {

    private final String name;
    private final List<LocalTime> times;
    private final boolean hourKey;
    private final java.util.function.Consumer<String> body;

    EtClockJob(String name, List<LocalTime> times, boolean hourKey, java.util.function.Consumer<String> body) {
      this.name = name;
      this.times = List.copyOf(times);
      this.hourKey = hourKey;
      this.body = body;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
      List<JobOccurrence> out = new ArrayList<>();
      for (LocalDate d = Et.date(fromExclusive); !d.isAfter(Et.date(toInclusive)); d = d.plusDays(1)) {
        for (LocalTime t : times) {
          Instant at = Et.at(d, t);
          if (at.isAfter(fromExclusive) && !at.isAfter(toInclusive)) {
            out.add(new JobOccurrence(at, hourKey ? d + ":" + String.format("%02d", t.getHour()) : d.toString()));
          }
        }
      }
      return out;
    }

    @Override
    public void run(String runKey) {
      body.accept(hourKey ? runKey.substring(0, runKey.indexOf(':')) : runKey);
    }

    @Override
    public CatchUpPolicy catchUp() {
      return CatchUpPolicy.LATEST_ONLY;
    }
  }
}
