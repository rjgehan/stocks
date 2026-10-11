package com.stocks.portfolio;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.Mark;
import com.stocks.portfolio.config.PerformanceProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The SPY total-return benchmark (§16.6), {@code benchmark_daily}:
 * <pre>
 * B_D = B_{D−1} × (P_D + Div_D) / P_{D−1}      P = day-end mark (raw), Div = dividend with ex-date D
 * intraday anchor t:  B_t = B_prevEOD(t) × SPY_mark(t) / P_prevEOD(t)
 * </pre>
 * The chain starts at B = 1 on its first row: 2025-12-31 once {@link BenchmarkBackfillJob} ran, otherwise the first
 * EOD the league recorded (only ratios {@code B_b / B_a} are ever used). Ratios at scale 12, HALF_EVEN.
 *
 * <p>Phase 2: the <b>extra benchmarks</b> ({@code stocks.performance.extra-benchmarks}, default QQQ) are chained the
 * same way under their own {@code symbol} (display only, P&amp;L Analysis §16.10); every method without a symbol
 * argument is the main benchmark (SPY), which the leaderboard uses.
 */
@Service
public class BenchmarkService {

  public static final int INDEX_SCALE = 12;

  /** A {@code benchmark_daily} row. */
  public record BenchmarkDay(LocalDate tradeDate, BigDecimal mark, BigDecimal dividend, BigDecimal indexValue) {}

  static final String MERGE = """
      MERGE INTO benchmark_daily b
      USING (VALUES (CAST(? AS VARCHAR(12)), CAST(? AS DATE), CAST(? AS NUMERIC(19,4)), CAST(? AS NUMERIC(19,6)),
                     CAST(? AS NUMERIC(28,12))))
        AS v(symbol, trade_date, mark, dividend, index_value)
      ON b.symbol = v.symbol AND b.trade_date = v.trade_date
      WHEN MATCHED THEN UPDATE SET mark = v.mark, dividend = v.dividend, index_value = v.index_value
      WHEN NOT MATCHED THEN INSERT (symbol, trade_date, mark, dividend, index_value)
        VALUES (v.symbol, v.trade_date, v.mark, v.dividend, v.index_value)""";

  private final JdbcClient jdbc;
  private final MarkResolver marks;
  private final PnlCalendar calendar;
  private final TimeSource time;
  private final String symbol;
  private final List<String> extras;

  public BenchmarkService(JdbcClient jdbc, MarkResolver marks, PnlCalendar calendar, TimeSource time,
                          PerformanceProperties properties) {
    this.jdbc = jdbc;
    this.marks = marks;
    this.calendar = calendar;
    this.time = time;
    this.symbol = properties.benchmarkSymbol();
    this.extras = properties.extraBenchmarkSymbols().stream().filter(s -> !s.equals(symbol)).toList();
  }

  /** The main benchmark (SPY): the leaderboard's {@code excessVsBenchmark} (§16.6). */
  public String symbol() {
    return symbol;
  }

  /** The display-only extra benchmarks (QQQ), in configuration order, without the main one. */
  public List<String> extraSymbols() {
    return extras;
  }

  /** The main benchmark, then the extras. */
  public List<String> symbols() {
    List<String> all = new java.util.ArrayList<>();
    all.add(symbol);
    all.addAll(extras);
    return List.copyOf(all);
  }

  // ------------------------------------------------------------------------------------------- reads

  public Optional<BenchmarkDay> day(LocalDate tradeDate) {
    return day(symbol, tradeDate);
  }

  public Optional<BenchmarkDay> day(String benchmark, LocalDate tradeDate) {
    return jdbc.sql("SELECT trade_date, mark, dividend, index_value FROM benchmark_daily "
            + "WHERE symbol = ? AND trade_date = ?")
        .params(benchmark, tradeDate).query(BenchmarkService::row).optional();
  }

  /** The latest row with {@code trade_date ≤ date}. */
  public Optional<BenchmarkDay> latestOnOrBefore(LocalDate date) {
    return latestOnOrBefore(symbol, date);
  }

  public Optional<BenchmarkDay> latestOnOrBefore(String benchmark, LocalDate date) {
    return jdbc.sql("SELECT trade_date, mark, dividend, index_value FROM benchmark_daily "
            + "WHERE symbol = ? AND trade_date <= ? ORDER BY trade_date DESC LIMIT 1")
        .params(benchmark, date).query(BenchmarkService::row).optional();
  }

  /** The latest row strictly before {@code date}. */
  public Optional<BenchmarkDay> latestBefore(LocalDate date) {
    return latestBefore(symbol, date);
  }

  public Optional<BenchmarkDay> latestBefore(String benchmark, LocalDate date) {
    return jdbc.sql("SELECT trade_date, mark, dividend, index_value FROM benchmark_daily "
            + "WHERE symbol = ? AND trade_date < ? ORDER BY trade_date DESC LIMIT 1")
        .params(benchmark, date).query(BenchmarkService::row).optional();
  }

  /** B at the EOD cut-off of {@code tradeDate}, if recorded. */
  public Optional<BigDecimal> indexAtEod(LocalDate tradeDate) {
    return indexAtEod(symbol, tradeDate);
  }

  public Optional<BigDecimal> indexAtEod(String benchmark, LocalDate tradeDate) {
    return day(benchmark, tradeDate).map(BenchmarkDay::indexValue);
  }

  /** Every row of {@code benchmark} with {@code fromExclusive < trade_date ≤ toInclusive}, oldest first. */
  public List<BenchmarkDay> between(String benchmark, LocalDate fromExclusive, LocalDate toInclusive) {
    return jdbc.sql("SELECT trade_date, mark, dividend, index_value FROM benchmark_daily "
            + "WHERE symbol = ? AND trade_date > ? AND trade_date <= ? ORDER BY trade_date")
        .params(benchmark, fromExclusive, toInclusive).query(BenchmarkService::row).list();
  }

  /**
   * B of an extra benchmark at an instant without a recorded mark (§16.6 extra benchmarks): the index of the last EOD
   * whose cut-off is at or before {@code t}.
   */
  public Optional<BigDecimal> indexAtLastEod(String benchmark, Instant t) {
    Optional<BenchmarkDay> base = latestOnOrBefore(benchmark, Et.date(t));
    if (base.isPresent() && calendar.cutoff(base.get().tradeDate()).isAfter(t)) {
      base = latestBefore(benchmark, base.get().tradeDate());
    }
    return base.map(BenchmarkDay::indexValue);
  }

  /**
   * B at instant {@code t} given SPY's mark then (§16.6 intraday anchor): the last EOD whose cut-off is at or before
   * {@code t}, scaled by {@code spyMark / P_prevEOD}. Empty without a mark or an earlier EOD row.
   */
  public Optional<BigDecimal> indexAt(Instant t, BigDecimal spyMark) {
    return indexAt(symbol, t, spyMark);
  }

  public Optional<BigDecimal> indexAt(String benchmark, Instant t, BigDecimal mark) {
    if (mark == null || mark.signum() <= 0) {
      return Optional.empty();
    }
    Optional<BenchmarkDay> base = latestOnOrBefore(benchmark, Et.date(t));
    if (base.isPresent() && calendar.cutoff(base.get().tradeDate()).isAfter(t)) {
      base = latestBefore(benchmark, base.get().tradeDate());
    }
    return base.map(b -> b.indexValue().multiply(mark).divide(b.mark(), INDEX_SCALE, RoundingMode.HALF_EVEN));
  }

  /** B now, from SPY's live mark; empty if SPY has no usable live mark or no EOD row exists yet. */
  public Optional<BigDecimal> liveIndex() {
    return liveIndex(symbol);
  }

  public Optional<BigDecimal> liveIndex(String benchmark) {
    Mark m = marks.live(benchmark, null, null);
    if (!usable(m)) {
      return Optional.empty();
    }
    return indexAt(benchmark, time.now(), m.price());
  }

  /**
   * A mark from market data (WS2 or the day-end mark), not one of {@link MarkResolver}'s own last-fill / cost
   * fallbacks (which have no basis): a weeks-old league fill must never stand in for the benchmark.
   */
  static boolean usable(Mark m) {
    return m.price() != null && m.price().signum() > 0 && m.quality() != DataQuality.NONE && m.basis() != null;
  }

  // ------------------------------------------------------------------------------------------- writes

  /**
   * Appends (or rewrites) {@code B_D} (§16.4 step 4). Returns the row, or empty when SPY has no usable mark for D.
   */
  public Optional<BenchmarkDay> recordEod(LocalDate tradeDate) {
    return recordEod(symbol, tradeDate);
  }

  public Optional<BenchmarkDay> recordEod(String benchmark, LocalDate tradeDate) {
    Instant cutoff = calendar.cutoff(tradeDate);
    Mark m = marks.endOfDay(benchmark, tradeDate, cutoff, null, null);
    if (!usable(m)) {
      return Optional.empty();
    }
    return Optional.of(write(benchmark, tradeDate, m.price(), dividendEx(benchmark, tradeDate)));
  }

  /** Writes D chained from the latest earlier row ({@code B = 1} when there is none). */
  public BenchmarkDay write(LocalDate tradeDate, BigDecimal mark, BigDecimal dividend) {
    return write(symbol, tradeDate, mark, dividend);
  }

  public BenchmarkDay write(String benchmark, LocalDate tradeDate, BigDecimal mark, BigDecimal dividend) {
    BigDecimal div = dividend == null ? BigDecimal.ZERO : dividend;
    Optional<BenchmarkDay> prev = latestBefore(benchmark, tradeDate);
    BigDecimal index = prev.map(p -> p.indexValue().multiply(mark.add(div))
            .divide(p.mark(), INDEX_SCALE, RoundingMode.HALF_EVEN))
        .orElse(BigDecimal.ONE.setScale(INDEX_SCALE));
    jdbc.sql(MERGE).params(benchmark, tradeDate, mark, div, index).update();
    return new BenchmarkDay(tradeDate, mark, div, index);
  }

  /** Rows after {@code fromExclusive}, oldest first. */
  public List<BenchmarkDay> after(LocalDate fromExclusive) {
    return after(symbol, fromExclusive);
  }

  public List<BenchmarkDay> after(String benchmark, LocalDate fromExclusive) {
    return jdbc.sql("SELECT trade_date, mark, dividend, index_value FROM benchmark_daily "
            + "WHERE symbol = ? AND trade_date > ? ORDER BY trade_date")
        .params(benchmark, fromExclusive).query(BenchmarkService::row).list();
  }

  /** {@code daily_mark.dividend_ex} of the benchmark for D (WS3 sets it when it applies a cash dividend, §14). */
  BigDecimal dividendEx(LocalDate tradeDate) {
    return dividendEx(symbol, tradeDate);
  }

  BigDecimal dividendEx(String benchmark, LocalDate tradeDate) {
    return jdbc.sql("SELECT dividend_ex FROM daily_mark WHERE symbol = ? AND trade_date = ?")
        .params(benchmark, tradeDate).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
  }

  private static BenchmarkDay row(ResultSet rs, int n) throws SQLException {
    return new BenchmarkDay(Jdbc.date(rs, "trade_date"), rs.getBigDecimal("mark"), rs.getBigDecimal("dividend"),
        rs.getBigDecimal("index_value"));
  }
}
