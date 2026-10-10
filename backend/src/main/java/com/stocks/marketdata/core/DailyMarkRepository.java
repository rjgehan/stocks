package com.stocks.marketdata.core;

import com.stocks.common.jdbc.Jdbc;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code daily_mark} (§8.1, §15.5): the SIP official close (16:20 job) and the day-end mark at {@code post_close_at}
 * (20:20 job) per symbol and trade date. Upserts are {@code MERGE} with every parameter cast (§8); {@code dividend_ex}
 * belongs to WS3 and is never touched here.
 */
public class DailyMarkRepository {

  /** A stored day-end mark. */
  public record DayEnd(String symbol, LocalDate tradeDate, BigDecimal mark, String basis, String quality) {}

  public static final String FINAL = "FINAL";
  public static final String PROVISIONAL = "PROVISIONAL";

  private final JdbcClient jdbc;

  public DailyMarkRepository(DataSource dataSource) {
    this.jdbc = JdbcClient.create(dataSource);
  }

  /** Sets {@code official_close}; a new row starts PROVISIONAL (its day-end mark is not known yet). */
  public void upsertOfficialClose(String symbol, LocalDate date, BigDecimal close, Instant now) {
    jdbc.sql("""
            MERGE INTO daily_mark d
            USING (VALUES (CAST(? AS VARCHAR(12)), CAST(? AS DATE), CAST(? AS NUMERIC(19,4)),
                           CAST(? AS TIMESTAMP WITH TIME ZONE)))
                  AS v(symbol, trade_date, official_close, updated_at)
            ON d.symbol = v.symbol AND d.trade_date = v.trade_date
            WHEN MATCHED THEN UPDATE SET official_close = v.official_close, updated_at = v.updated_at
            WHEN NOT MATCHED THEN INSERT (symbol, trade_date, official_close, quality, updated_at)
                 VALUES (v.symbol, v.trade_date, v.official_close, 'PROVISIONAL', v.updated_at)""")
        .params(symbol, date, close, Jdbc.ts(now)).update();
  }

  /** Sets the day-end mark, its basis and the row quality ({@code FINAL} when SIP-based). */
  public void upsertDayEnd(String symbol, LocalDate date, BigDecimal mark, String basis, String quality, Instant now) {
    jdbc.sql("""
            MERGE INTO daily_mark d
            USING (VALUES (CAST(? AS VARCHAR(12)), CAST(? AS DATE), CAST(? AS NUMERIC(19,4)), CAST(? AS VARCHAR(16)),
                           CAST(? AS VARCHAR(12)), CAST(? AS TIMESTAMP WITH TIME ZONE)))
                  AS v(symbol, trade_date, day_end_mark, day_end_basis, quality, updated_at)
            ON d.symbol = v.symbol AND d.trade_date = v.trade_date
            WHEN MATCHED THEN UPDATE SET day_end_mark = v.day_end_mark, day_end_basis = v.day_end_basis,
                 quality = v.quality, updated_at = v.updated_at
            WHEN NOT MATCHED THEN INSERT (symbol, trade_date, day_end_mark, day_end_basis, quality, updated_at)
                 VALUES (v.symbol, v.trade_date, v.day_end_mark, v.day_end_basis, v.quality, v.updated_at)""")
        .params(symbol, date, new SqlParameterValue(Types.NUMERIC, mark), new SqlParameterValue(Types.VARCHAR, basis),
            quality, Jdbc.ts(now))
        .update();
  }

  public Optional<BigDecimal> officialClose(String symbol, LocalDate date) {
    return jdbc.sql("SELECT official_close FROM daily_mark WHERE symbol = ? AND trade_date = ?")
        .params(symbol, date).query((rs, n) -> rs.getBigDecimal("official_close")).optional()
        .filter(v -> v != null);
  }

  public Optional<BigDecimal> dayEndMark(String symbol, LocalDate date) {
    return jdbc.sql("SELECT day_end_mark FROM daily_mark WHERE symbol = ? AND trade_date = ?")
        .params(symbol, date).query((rs, n) -> rs.getBigDecimal("day_end_mark")).optional()
        .filter(v -> v != null);
  }

  /** The latest day-end mark dated on or before {@code date}. */
  public Optional<DayEnd> lastDayEnd(String symbol, LocalDate onOrBefore) {
    return jdbc.sql("""
            SELECT symbol, trade_date, day_end_mark, day_end_basis, quality FROM daily_mark
            WHERE symbol = ? AND trade_date <= ? AND day_end_mark IS NOT NULL
            ORDER BY trade_date DESC LIMIT 1""")
        .params(symbol, onOrBefore)
        .query((rs, n) -> new DayEnd(rs.getString("symbol"), Jdbc.date(rs, "trade_date"),
            rs.getBigDecimal("day_end_mark"), rs.getString("day_end_basis"), rs.getString("quality")))
        .optional();
  }

  /** Official closes of {@code date} for the given symbols (missing ones are absent). */
  public Map<String, BigDecimal> officialCloses(LocalDate date, Set<String> symbols) {
    Map<String, BigDecimal> out = new HashMap<>();
    if (symbols.isEmpty()) {
      return out;
    }
    jdbc.sql("SELECT symbol, official_close FROM daily_mark WHERE trade_date = ? AND official_close IS NOT NULL")
        .param(date).query((rs, n) -> {
          String s = rs.getString("symbol");
          if (symbols.contains(s)) {
            out.put(s, rs.getBigDecimal("official_close"));
          }
          return null;
        }).list();
    return out;
  }

  /** Symbols of {@code date} that already have a day-end mark of the given quality (or any, when null). */
  public Set<String> withDayEnd(LocalDate date, String qualityOrNull) {
    List<String> rows = jdbc.sql("SELECT symbol, quality FROM daily_mark WHERE trade_date = ? AND day_end_mark IS NOT NULL")
        .param(date).query((rs, n) -> qualityOrNull == null || qualityOrNull.equals(rs.getString("quality"))
            ? rs.getString("symbol") : null).list();
    Set<String> out = new TreeSet<>();
    rows.stream().filter(s -> s != null).forEach(out::add);
    return out;
  }

  /** Symbols of {@code date} whose row is PROVISIONAL (re-marked the next day). */
  public List<String> provisional(LocalDate date) {
    return new ArrayList<>(withDayEnd(date, PROVISIONAL));
  }
}
