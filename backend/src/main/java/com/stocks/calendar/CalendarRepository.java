package com.stocks.calendar;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.calendar.CalendarOverride;
import com.stocks.contract.calendar.TradingDay;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code trading_day} and {@code calendar_override} (§8.1). Only {@code com.stocks.calendar} reads these tables (§8
 * portability rules, enforced by {@code ArchitectureTest}). Every instant is bound through {@link Jdbc#ts} and every
 * {@code MERGE} parameter is cast to its column type (§8), so the same SQL runs on H2 and PostgreSQL.
 */
public class CalendarRepository {

  private static final String DAY_COLUMNS =
      "trade_date, pre_open_at, rth_open_at, rth_close_at, post_close_at, settlement_date, early_close, source";

  private final JdbcClient jdbc;
  private final JdbcTemplate template;

  public CalendarRepository(DataSource dataSource) {
    this.jdbc = JdbcClient.create(dataSource);
    this.template = new JdbcTemplate(dataSource);
  }

  public List<TradingDay> loadTradingDays() {
    return jdbc.sql("SELECT " + DAY_COLUMNS + " FROM trading_day ORDER BY trade_date")
        .query(CalendarRepository::mapDay).list();
  }

  public Set<LocalDate> tradingDayDates() {
    return new HashSet<>(jdbc.sql("SELECT trade_date FROM trading_day")
        .query((rs, n) -> Jdbc.date(rs, "trade_date")).list());
  }

  /** {@code [min, max]} trade dates of {@code source=ALPACA} rows, if any. */
  public Optional<LocalDate[]> alpacaWindow() {
    return jdbc.sql("SELECT MIN(trade_date) AS lo, MAX(trade_date) AS hi FROM trading_day WHERE source = 'ALPACA'")
        .query((rs, n) -> {
          LocalDate lo = Jdbc.date(rs, "lo");
          LocalDate hi = Jdbc.date(rs, "hi");
          return lo == null || hi == null ? null : new LocalDate[] {lo, hi};
        })
        .optional();
  }

  /** Inserts rows (their dates must be missing), in batches. */
  public int insert(List<TradingDay> days, Instant now) {
    if (days.isEmpty()) {
      return 0;
    }
    List<Object[]> args = new ArrayList<>(days.size());
    for (TradingDay d : days) {
      args.add(new Object[] {d.tradeDate(), Jdbc.ts(d.preOpenAt()), Jdbc.ts(d.rthOpenAt()), Jdbc.ts(d.rthCloseAt()),
          Jdbc.ts(d.postCloseAt()), d.settlementDate(), d.earlyClose(), TradingDays.dbSource(d.source()),
          Jdbc.ts(now)});
    }
    int[] types = {Types.DATE, Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE,
        Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE, Types.DATE, Types.BOOLEAN, Types.VARCHAR,
        Types.TIMESTAMP_WITH_TIMEZONE};
    template.batchUpdate("INSERT INTO trading_day (" + DAY_COLUMNS + ", updated_at) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", args, types);
    return args.size();
  }

  /** Upserts provider rows (MERGE, §8 cast-everything form). */
  public void upsert(List<TradingDay> days, Instant now) {
    String sql = """
        MERGE INTO trading_day t
        USING (VALUES (CAST(? AS DATE), CAST(? AS TIMESTAMP WITH TIME ZONE), CAST(? AS TIMESTAMP WITH TIME ZONE),
                       CAST(? AS TIMESTAMP WITH TIME ZONE), CAST(? AS TIMESTAMP WITH TIME ZONE), CAST(? AS DATE),
                       CAST(? AS BOOLEAN), CAST(? AS VARCHAR(10)), CAST(? AS TIMESTAMP WITH TIME ZONE)))
              AS v(trade_date, pre_open_at, rth_open_at, rth_close_at, post_close_at, settlement_date, early_close,
                   source, updated_at)
        ON t.trade_date = v.trade_date
        WHEN MATCHED THEN UPDATE SET pre_open_at = v.pre_open_at, rth_open_at = v.rth_open_at,
             rth_close_at = v.rth_close_at, post_close_at = v.post_close_at, settlement_date = v.settlement_date,
             early_close = v.early_close, source = v.source, updated_at = v.updated_at
        WHEN NOT MATCHED THEN INSERT (trade_date, pre_open_at, rth_open_at, rth_close_at, post_close_at,
             settlement_date, early_close, source, updated_at)
             VALUES (v.trade_date, v.pre_open_at, v.rth_open_at, v.rth_close_at, v.post_close_at, v.settlement_date,
                     v.early_close, v.source, v.updated_at)
        """;
    List<Object[]> args = new ArrayList<>(days.size());
    for (TradingDay d : days) {
      args.add(new Object[] {d.tradeDate(), Jdbc.ts(d.preOpenAt()), Jdbc.ts(d.rthOpenAt()), Jdbc.ts(d.rthCloseAt()),
          Jdbc.ts(d.postCloseAt()), d.settlementDate(), d.earlyClose(), TradingDays.dbSource(d.source()),
          Jdbc.ts(now)});
    }
    if (!args.isEmpty()) {
      template.batchUpdate(sql, args);
    }
  }

  /** Deletes rows in {@code [from, to]} whose date is not in {@code keep} (a provider says they are closed). */
  public int deleteNotIn(LocalDate from, LocalDate to, Set<LocalDate> keep) {
    List<LocalDate> doomed = jdbc.sql("SELECT trade_date FROM trading_day WHERE trade_date BETWEEN ? AND ?")
        .params(from, to).query((rs, n) -> Jdbc.date(rs, "trade_date")).list()
        .stream().filter(d -> !keep.contains(d)).toList();
    for (LocalDate d : doomed) {
      jdbc.sql("DELETE FROM trading_day WHERE trade_date = ?").param(d).update();
    }
    return doomed.size();
  }

  // ------------------------------------------------------------------------------------------- overrides

  public List<CalendarOverride> overrides(LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.sql("""
            SELECT o.trade_date, o.closed, o.rth_close_at, o.post_close_at, o.reason, o.created_at, p.handle
            FROM calendar_override o LEFT JOIN player p ON p.id = o.created_by
            WHERE o.trade_date BETWEEN ? AND ? ORDER BY o.trade_date""")
        .params(fromInclusive, toInclusive).query(CalendarRepository::mapOverride).list();
  }

  public List<CalendarOverride> allOverrides() {
    return overrides(LocalDate.of(1900, 1, 1), LocalDate.of(9999, 12, 31));
  }

  public Optional<CalendarOverride> override(LocalDate date) {
    return jdbc.sql("""
            SELECT o.trade_date, o.closed, o.rth_close_at, o.post_close_at, o.reason, o.created_at, p.handle
            FROM calendar_override o LEFT JOIN player p ON p.id = o.created_by WHERE o.trade_date = ?""")
        .param(date).query(CalendarRepository::mapOverride).optional();
  }

  /** Creates or replaces the override for a date (MERGE; nullable close times bound with their SQL type). */
  public void putOverride(LocalDate date, boolean closed, Instant rthCloseAtOrNull, Instant postCloseAtOrNull,
                          String reason, long actorPlayerId, Instant now) {
    jdbc.sql("""
            MERGE INTO calendar_override o
            USING (VALUES (CAST(? AS DATE), CAST(? AS BOOLEAN), CAST(? AS TIMESTAMP WITH TIME ZONE),
                           CAST(? AS TIMESTAMP WITH TIME ZONE), CAST(? AS VARCHAR(200)), CAST(? AS BIGINT),
                           CAST(? AS TIMESTAMP WITH TIME ZONE)))
                  AS v(trade_date, closed, rth_close_at, post_close_at, reason, created_by, created_at)
            ON o.trade_date = v.trade_date
            WHEN MATCHED THEN UPDATE SET closed = v.closed, rth_close_at = v.rth_close_at,
                 post_close_at = v.post_close_at, reason = v.reason, created_by = v.created_by,
                 created_at = v.created_at
            WHEN NOT MATCHED THEN INSERT (trade_date, closed, rth_close_at, post_close_at, reason, created_by,
                 created_at)
                 VALUES (v.trade_date, v.closed, v.rth_close_at, v.post_close_at, v.reason, v.created_by,
                         v.created_at)""")
        .params(date, closed, Jdbc.tsParam(rthCloseAtOrNull), Jdbc.tsParam(postCloseAtOrNull),
            new SqlParameterValue(Types.VARCHAR, reason), actorPlayerId, Jdbc.ts(now))
        .update();
  }

  public boolean deleteOverride(LocalDate date) {
    return jdbc.sql("DELETE FROM calendar_override WHERE trade_date = ?").param(date).update() > 0;
  }

  // ------------------------------------------------------------------------------------------- mapping

  private static TradingDay mapDay(ResultSet rs, int n) throws SQLException {
    return new TradingDay(Jdbc.date(rs, "trade_date"), Jdbc.instant(rs, "pre_open_at"), Jdbc.instant(rs, "rth_open_at"),
        Jdbc.instant(rs, "rth_close_at"), Jdbc.instant(rs, "post_close_at"), Jdbc.date(rs, "settlement_date"),
        rs.getBoolean("early_close"), TradingDays.fromDbSource(rs.getString("source")));
  }

  private static CalendarOverride mapOverride(ResultSet rs, int n) throws SQLException {
    return new CalendarOverride(Jdbc.date(rs, "trade_date"), rs.getBoolean("closed"), Jdbc.instant(rs, "rth_close_at"),
        Jdbc.instant(rs, "post_close_at"), rs.getString("reason"), rs.getString("handle"),
        Jdbc.instant(rs, "created_at"));
  }
}
