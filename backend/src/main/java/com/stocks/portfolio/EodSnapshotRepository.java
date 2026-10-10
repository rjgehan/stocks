package com.stocks.portfolio;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.portfolio.EodSnapshot.HoldingRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code equity_snapshot} and {@code snapshot_holding} (§8.1, §16.4), written only by the EOD job. The snapshot row is
 * a cast-everything {@code MERGE} (§8 portability rules); its holdings are replaced in the same transaction, so a
 * re-run of the job for the same day is idempotent.
 */
@Repository
public class EodSnapshotRepository {

  private static final String COLUMNS = """
      account_id, trade_date, as_of, cash, long_market_value, dividends_receivable, equity, net_contributions, units,
      unit_value, unit_series, flows_in_day, pnl_day, fills_day, marks_quality, computed_at""";

  static final String MERGE_SNAPSHOT = """
      MERGE INTO equity_snapshot s
      USING (VALUES (CAST(? AS BIGINT), CAST(? AS DATE), CAST(? AS TIMESTAMP WITH TIME ZONE), CAST(? AS NUMERIC(19,2)),
                     CAST(? AS NUMERIC(19,2)), CAST(? AS NUMERIC(19,2)), CAST(? AS NUMERIC(19,2)),
                     CAST(? AS NUMERIC(19,2)), CAST(? AS NUMERIC(38,18)), CAST(? AS NUMERIC(38,18)), CAST(? AS INT),
                     CAST(? AS NUMERIC(19,2)), CAST(? AS NUMERIC(19,2)), CAST(? AS INT), CAST(? AS VARCHAR(24)),
                     CAST(? AS TIMESTAMP WITH TIME ZONE)))
        AS v(account_id, trade_date, as_of, cash, lmv, receivable, equity, nc, units, unit_value, unit_series,
             flows, pnl, fills, quality, computed_at)
      ON s.account_id = v.account_id AND s.trade_date = v.trade_date
      WHEN MATCHED THEN UPDATE SET as_of = v.as_of, cash = v.cash, long_market_value = v.lmv,
        dividends_receivable = v.receivable, equity = v.equity, net_contributions = v.nc, units = v.units,
        unit_value = v.unit_value, unit_series = v.unit_series, flows_in_day = v.flows, pnl_day = v.pnl,
        fills_day = v.fills, marks_quality = v.quality, computed_at = v.computed_at
      WHEN NOT MATCHED THEN INSERT (account_id, trade_date, as_of, cash, long_market_value, dividends_receivable,
        equity, net_contributions, units, unit_value, unit_series, flows_in_day, pnl_day, fills_day, marks_quality,
        computed_at)
        VALUES (v.account_id, v.trade_date, v.as_of, v.cash, v.lmv, v.receivable, v.equity, v.nc, v.units,
                v.unit_value, v.unit_series, v.flows, v.pnl, v.fills, v.quality, v.computed_at)""";

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  public EodSnapshotRepository(JdbcClient jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  /** Writes the snapshot and replaces its holdings, in one transaction. */
  public void upsert(EodSnapshot s) {
    tx.executeWithoutResult(status -> {
      jdbc.sql(MERGE_SNAPSHOT)
          .params(s.accountId(), s.tradeDate(), Jdbc.ts(s.asOf()), s.cash(), s.longMarketValue(),
              s.dividendsReceivable(), s.equity(), s.netContributions(), s.units(), s.unitValue(), s.unitSeries(),
              s.flowsInDay(), s.pnlDay(), s.fillsDay(), s.marksQuality().name(), Jdbc.ts(s.computedAt()))
          .update();
      jdbc.sql("DELETE FROM snapshot_holding WHERE account_id = ? AND trade_date = ?")
          .params(s.accountId(), s.tradeDate()).update();
      if (s.holdings() != null) {
        for (HoldingRow h : s.holdings()) {
          jdbc.sql("""
                  INSERT INTO snapshot_holding (account_id, trade_date, symbol, qty, mark, market_value)
                  VALUES (?, ?, ?, ?, ?, ?)""")
              .params(s.accountId(), s.tradeDate(), h.symbol(), h.qty(), h.mark(), h.marketValue())
              .update();
        }
      }
    });
  }

  public Optional<EodSnapshot> find(long accountId, LocalDate tradeDate) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM equity_snapshot WHERE account_id = ? AND trade_date = ?")
        .params(accountId, tradeDate).query(EodSnapshotRepository::snapshot).optional();
  }

  /** Every snapshot of {@code tradeDate}, keyed by account id. */
  public Map<Long, EodSnapshot> byDate(LocalDate tradeDate) {
    Map<Long, EodSnapshot> out = new HashMap<>();
    jdbc.sql("SELECT " + COLUMNS + " FROM equity_snapshot WHERE trade_date = ?")
        .param(tradeDate).query(EodSnapshotRepository::snapshot).list()
        .forEach(s -> out.put(s.accountId(), s));
    return out;
  }

  /** Snapshots of the given accounts with {@code fromExclusive < trade_date ≤ toInclusive}, oldest first. */
  public List<EodSnapshot> series(Collection<Long> accountIds, LocalDate fromExclusive, LocalDate toInclusive) {
    if (accountIds.isEmpty()) {
      return List.of();
    }
    String in = accountIds.stream().map(id -> "?").collect(Collectors.joining(", "));
    List<Object> params = new ArrayList<>(accountIds);
    params.add(fromExclusive);
    params.add(toInclusive);
    return jdbc.sql("SELECT " + COLUMNS + " FROM equity_snapshot WHERE account_id IN (" + in + ") "
            + "AND trade_date > ? AND trade_date <= ? ORDER BY trade_date, account_id")
        .params(params).query(EodSnapshotRepository::snapshot).list();
  }

  /** Every snapshot with {@code trade_date > fromExclusive}, oldest first (the leaderboard's EOD series). */
  public List<EodSnapshot> allAfter(LocalDate fromExclusive) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM equity_snapshot WHERE trade_date > ? ORDER BY trade_date, account_id")
        .param(fromExclusive).query(EodSnapshotRepository::snapshot).list();
  }

  public List<HoldingRow> holdings(long accountId, LocalDate tradeDate) {
    return jdbc.sql("""
            SELECT symbol, qty, mark, market_value FROM snapshot_holding
             WHERE account_id = ? AND trade_date = ? ORDER BY market_value DESC, symbol""")
        .params(accountId, tradeDate)
        .query((rs, n) -> new HoldingRow(rs.getString("symbol"), rs.getBigDecimal("qty"), rs.getBigDecimal("mark"),
            rs.getBigDecimal("market_value")))
        .list();
  }

  /** The latest snapshot date of an account, if any. */
  public Optional<LocalDate> latestDate(long accountId) {
    return jdbc.sql("SELECT trade_date FROM equity_snapshot WHERE account_id = ? ORDER BY trade_date DESC LIMIT 1")
        .param(accountId).query((rs, n) -> Jdbc.date(rs, 1)).optional();
  }

  private static EodSnapshot snapshot(ResultSet rs, int n) throws SQLException {
    return new EodSnapshot(rs.getLong("account_id"), Jdbc.date(rs, "trade_date"), Jdbc.instant(rs, "as_of"),
        rs.getBigDecimal("cash"), rs.getBigDecimal("long_market_value"), rs.getBigDecimal("dividends_receivable"),
        rs.getBigDecimal("equity"), rs.getBigDecimal("net_contributions"), rs.getBigDecimal("units"),
        rs.getBigDecimal("unit_value"), rs.getInt("unit_series"), rs.getBigDecimal("flows_in_day"),
        rs.getBigDecimal("pnl_day"), rs.getInt("fills_day"), DataQuality.valueOf(rs.getString("marks_quality")),
        Jdbc.instant(rs, "computed_at"), null, true);
  }
}
