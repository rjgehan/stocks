package com.stocks.trading.options;

import com.stocks.common.domain.OptionSymbol;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.contract.marketdata.DailyMarkService;
import com.stocks.trading.engine.EngineCalls;
import com.stocks.trading.engine.EngineCommand;
import com.stocks.trading.engine.OptionExpiryHandler;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code OPTION_EXPIRY} (§30, §25): 30 minutes after the regular close (16:30 ET, 13:30 on early-close days) and again
 * 30 minutes after the post-market close (20:30 ET) of every trading day, every held option contract whose expiration
 * is on or before that day is cash-settled ({@code SettleOptionExpiryCmd}) at its intrinsic value from the
 * underlying's official close on the expiration date. The close comes from {@code daily_mark.official_close}
 * ({@code DailyMarkService.ensureOfficialCloses} fetches it first); a contract whose underlying has no close yet is
 * left for the next run, so the second run and the next days' runs (catch-up ALL_MISSED after downtime) settle what
 * the first could not. Settling is idempotent, so re-runs are harmless.
 */
@Component
public class OptionExpiryJob implements ScheduledJob {

  private static final Logger log = LoggerFactory.getLogger(OptionExpiryJob.class);
  public static final String NAME = "OPTION_EXPIRY";
  static final Duration AFTER_CLOSE = Duration.ofMinutes(30);

  private final EngineCalls engine;
  private final DailyMarkService dailyMarks;
  private final JdbcClient jdbc;

  public OptionExpiryJob(EngineCalls engine, DailyMarkService dailyMarks, JdbcClient jdbc) {
    this.engine = engine;
    this.dailyMarks = dailyMarks;
    this.jdbc = jdbc;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    List<JobOccurrence> out = new ArrayList<>();
    for (LocalDate d = Et.date(fromExclusive); !d.isAfter(Et.date(toInclusive)); d = d.plusDays(1)) {
      Optional<TradingDay> day = cal.day(d);
      if (day.isEmpty()) {
        continue;
      }
      add(out, day.get().rthCloseAt().plus(AFTER_CLOSE), d + ":CLOSE", fromExclusive, toInclusive);
      add(out, day.get().postCloseAt().plus(AFTER_CLOSE), d + ":LATE", fromExclusive, toInclusive);
    }
    return out;
  }

  private static void add(List<JobOccurrence> out, Instant at, String key, Instant fromExclusive, Instant to) {
    if (at.isAfter(fromExclusive) && !at.isAfter(to)) {
      out.add(new JobOccurrence(at, key));
    }
  }

  @Override
  public void run(String runKey) {
    settleThrough(LocalDate.parse(runKey.substring(0, 10)));
  }

  /** Settles every held contract expiring on or before {@code date}; returns how many contracts were settled. */
  public int settleThrough(LocalDate date) {
    Map<LocalDate, Set<String>> byExpiration = new TreeMap<>();
    for (String symbol : heldOptions()) {
      OptionSymbol.parse(symbol).filter(c -> !c.expiration().isAfter(date))
          .ifPresent(c -> byExpiration.computeIfAbsent(c.expiration(), k -> new TreeSet<>()).add(symbol));
    }
    int settled = 0;
    for (Map.Entry<LocalDate, Set<String>> e : byExpiration.entrySet()) {
      LocalDate expiration = e.getKey();
      Set<String> underlyings = new TreeSet<>();
      e.getValue().forEach(s -> underlyings.add(OptionSymbol.parse(s).orElseThrow().underlying()));
      try {
        dailyMarks.ensureOfficialCloses(expiration, underlyings);
      } catch (RuntimeException ex) {
        log.warn("Option expiry {}: official closes failed: {}", expiration, ex.toString());
      }
      for (String symbol : e.getValue()) {
        String underlying = OptionSymbol.parse(symbol).orElseThrow().underlying();
        Optional<BigDecimal> close = dailyMarks.officialClose(underlying, expiration);
        if (close.isEmpty() || close.get().signum() <= 0) {
          log.warn("Option expiry: no official close for {} on {} yet; {} waits for the next run", underlying,
              expiration, symbol);
          continue;
        }
        OptionExpiryHandler.Result res = engine.call(new EngineCommand.SettleOptionExpiryCmd(symbol, close.get()));
        log.info("Option expiry {}: {} closed at {}, intrinsic {}, {} contracts in {} accounts, ${} credited", symbol,
            underlying, close.get(), res.intrinsic(), res.contracts(), res.accounts(), res.cash());
        settled++;
      }
    }
    return settled;
  }

  private List<String> heldOptions() {
    return jdbc.sql("SELECT DISTINCT p.symbol FROM position p JOIN account a ON a.id = p.account_id "
            + "WHERE p.qty > 0 AND a.status = 'ACTIVE'").query(String.class).list().stream()
        .filter(OptionSymbol::isOption).toList();
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.ALL_MISSED;
  }
}
