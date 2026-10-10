package com.stocks.trading.engine;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Prices;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.jobs.PeriodicTask;
import com.stocks.contract.marketdata.BarRequest;
import com.stocks.contract.marketdata.BarSeries;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.security.RequestMeta;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Invariant I10 (§8.4, §25), warnings only: every execution price lies within the SIP 1-minute
 * {@code [low − spread, high + spread]} of its minute, where {@code spread} is the reference quote's spread at
 * execution (at least one tick). A {@link PeriodicTask} ({@code FILL_QUALITY_AUDIT}, every 60 s) checks executions
 * 16–30 minutes old, after the 15-minute embargo of the free SIP history; an execution whose minute has no SIP bar yet
 * (only the IEX tail, or nothing) is retried on later ticks until it leaves the window. A finding is logged at WARN and
 * written once to {@code audit_log} as {@code SYSTEM/FILL_QUALITY_WARNING} (target {@code execution}); it never halts
 * the engine. Read-only on the trading tables; it runs on the job scheduler, never on the engine thread.
 */
@Component
public class FillQualityAudit implements PeriodicTask {

  public static final String NAME = "FILL_QUALITY_AUDIT";
  public static final String ACTION = "FILL_QUALITY_WARNING";
  static final Duration EMBARGO = Duration.ofMinutes(16);
  static final Duration WINDOW = Duration.ofMinutes(30);
  private static final int RECENT = 100;
  private static final Logger log = LoggerFactory.getLogger(FillQualityAudit.class);

  /** One execution outside its minute's SIP range. */
  public record Finding(String executionId, String symbol, Instant executedAt, BigDecimal price, BigDecimal low,
                        BigDecimal high, BigDecimal spread) {}

  /** What one pass did: executions checked against a bar, findings, and executions still waiting for a SIP bar. */
  public record Pass(int checked, List<Finding> findings, int awaitingBar) {}

  private record Row(String executionId, String symbol, Instant executedAt, BigDecimal price, BigDecimal refBid,
                     BigDecimal refAsk) {}

  private final JdbcClient jdbc;
  private final MarketDataService marketData;
  private final AuditService audit;
  private final TimeSource time;
  private final Map<String, Instant> done = new ConcurrentHashMap<>();
  private final Deque<Finding> recent = new ArrayDeque<>();

  public FillQualityAudit(JdbcClient jdbc, MarketDataService marketData, AuditService audit, TimeSource time) {
    this.jdbc = jdbc;
    this.marketData = marketData;
    this.audit = audit;
    this.time = time;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Duration interval() {
    return Duration.ofSeconds(60);
  }

  @Override
  public boolean sessionsOnly() {
    return false;
  }

  @Override
  public void tick() {
    run(time.now());
  }

  /** One pass over the executions 16–30 minutes old at {@code now}. */
  public synchronized Pass run(Instant now) {
    Instant from = now.minus(WINDOW);
    Instant to = now.minus(EMBARGO);
    done.values().removeIf(at -> at.isBefore(from.minus(Duration.ofMinutes(1))));
    List<Row> rows = jdbc.sql("""
            SELECT public_id, symbol, executed_at, price, ref_bid, ref_ask FROM execution
             WHERE executed_at >= ? AND executed_at <= ? ORDER BY executed_at, id""")
        .params(Jdbc.ts(from), Jdbc.ts(to))
        .query((rs, n) -> new Row(rs.getString("public_id"), rs.getString("symbol"), Jdbc.instant(rs, "executed_at"),
            rs.getBigDecimal("price"), rs.getBigDecimal("ref_bid"), rs.getBigDecimal("ref_ask")))
        .list();
    int checked = 0;
    int waiting = 0;
    List<Finding> findings = new ArrayList<>();
    for (Row r : rows) {
      if (done.containsKey(r.executionId())) {
        continue;
      }
      Bar bar = sipBar(r.symbol(), r.executedAt());
      if (bar == null) {
        waiting++;
        continue;
      }
      checked++;
      done.put(r.executionId(), r.executedAt());
      Finding f = check(r, bar);
      if (f != null) {
        findings.add(f);
        report(f);
      }
    }
    return new Pass(checked, List.copyOf(findings), waiting);
  }

  /** The latest findings, newest last (at most 100; in memory). */
  public synchronized List<Finding> recent() {
    return List.copyOf(recent);
  }

  /** I10 for one execution, or null when it lies within the bar's range widened by the spread. */
  static Finding check(String executionId, String symbol, Instant executedAt, BigDecimal price, BigDecimal refBid,
                       BigDecimal refAsk, Bar bar) {
    BigDecimal tick = Prices.tick(price);
    BigDecimal spread = refBid != null && refAsk != null && refAsk.compareTo(refBid) >= 0
        ? refAsk.subtract(refBid).max(tick) : tick;
    BigDecimal lowest = bar.low().subtract(spread);
    BigDecimal highest = bar.high().add(spread);
    if (price.compareTo(lowest) >= 0 && price.compareTo(highest) <= 0) {
      return null;
    }
    return new Finding(executionId, symbol, executedAt, price, bar.low(), bar.high(), spread);
  }

  private static Finding check(Row r, Bar bar) {
    return check(r.executionId(), r.symbol(), r.executedAt(), r.price(), r.refBid(), r.refAsk(), bar);
  }

  /** The consolidated (SIP) 1-minute bar of the execution's minute, raw prices; null when not available. */
  private Bar sipBar(String symbol, Instant executedAt) {
    Instant minute = executedAt.truncatedTo(ChronoUnit.MINUTES);
    BarSeries series;
    try {
      series = marketData.bars(new BarRequest(symbol, null, Timeframe.MIN1, minute, minute.plus(Duration.ofMinutes(1)),
          null, AdjustmentMode.RAW, true));
    } catch (RuntimeException e) {
      return null;            // the provider is down: retried on the next tick
    }
    if (series == null || series.bars() == null) {
      return null;
    }
    for (Bar b : series.bars()) {
      boolean iexTail = b.partialVolume() || (series.tailFrom() != null && !b.start().isBefore(series.tailFrom()));
      if (minute.equals(b.start()) && !iexTail && b.low() != null && b.high() != null) {
        return b;
      }
    }
    return null;
  }

  private void report(Finding f) {
    log.warn("I10 fill quality: execution {} {} at {} is outside the SIP minute [{} − {}, {} + {}] of {}",
        f.executionId(), f.symbol(), f.price().toPlainString(), f.low().toPlainString(), f.spread().toPlainString(),
        f.high().toPlainString(), f.spread().toPlainString(), f.executedAt());
    recent.addLast(f);
    while (recent.size() > RECENT) {
      recent.removeFirst();
    }
    boolean written = jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = ? AND target_id = ?")
        .params(ACTION, f.executionId()).query(Integer.class).single() > 0;
    if (!written) {
      audit.record(AuditEntry.of(AuditCategory.SYSTEM, ACTION, AuditOutcome.ERROR)
          .withMeta(RequestMeta.system(null)).withTarget("execution", f.executionId())
          .withDetail("invariant", "I10")
          .withDetail("symbol", f.symbol())
          .withDetail("executedAt", f.executedAt().toString())
          .withDetail("price", f.price())
          .withDetail("barLow", f.low())
          .withDetail("barHigh", f.high())
          .withDetail("spread", f.spread()));
    }
  }
}
