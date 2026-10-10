package com.stocks.trading.session;

import com.stocks.common.domain.TransitionType;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionTransition;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.trading.engine.EngineCalls;
import com.stocks.trading.engine.EngineCommand;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code SESSION_TRANSITION} (§9.4, §25): one occurrence per calendar transition (PRE_OPEN, RTH_OPEN, RTH_CLOSE,
 * POST_CLOSE) with run key {@code <date>:<type>}; each run submits a {@code TransitionCmd}. Catch-up replays every
 * missed transition in time order, but orders never fill for time the app was down.
 */
@Component
public class SessionTransitionJob implements ScheduledJob {

  public static final String NAME = "SESSION_TRANSITION";

  private final EngineCalls engine;

  public SessionTransitionJob(EngineCalls engine) {
    this.engine = engine;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    return cal.transitionsBetween(fromExclusive, toInclusive).stream()
        .map(t -> new JobOccurrence(t.at(), runKey(t)))
        .toList();
  }

  static String runKey(SessionTransition t) {
    return t.tradingDate() + ":" + t.type().name();
  }

  @Override
  public void run(String runKey) {
    int sep = runKey.indexOf(':');
    LocalDate date = LocalDate.parse(runKey.substring(0, sep));
    TransitionType type = TransitionType.valueOf(runKey.substring(sep + 1));
    engine.call(new EngineCommand.TransitionCmd(date, type));
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.ALL_MISSED;
  }
}
