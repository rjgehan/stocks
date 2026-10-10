package com.stocks.league;

import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import com.stocks.portfolio.PortfolioRepository.TransferRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * League aggregates for one recompute (§16.5), a handful of grouped queries per period instead of per-player loops:
 * fills and symbols (orders whose <b>first</b> execution falls in the window, across runs), closing sells and wins,
 * API-key fills, every applied transfer, previous ranks and the previous-EOD top holdings. Also
 * {@code leaderboard_snapshot} writes. Read-only on WS3's tables.
 */
@Repository
public class LeagueRepository {

  /**
   * Trading activity of one player in a window; {@code grossProfit} and {@code grossLoss} (both ≥ 0) sum the realized
   * P&amp;L of the winning and losing closing sells (§16.8 profit factor).
   */
  public record Activity(int fills, int symbols, int closingSells, int wins, int apiFills, BigDecimal grossProfit,
                         BigDecimal grossLoss) {
    static final Activity NONE = new Activity(0, 0, 0, 0, 0, Money.ZERO, Money.ZERO);
  }

  /** One player's rank in one {@code leaderboard_snapshot} (§16.8 rank history); {@code ranked} rows that day. */
  public record RankRow(LocalDate date, Integer rank, int ranked) {}

  /** One unit-value point of a run's EOD series. */
  public record SeriesPoint(long accountId, LocalDate tradeDate, Instant asOf, BigDecimal unitValue, int unitSeries,
                            BigDecimal equity) {}

  /** A {@code leaderboard_snapshot} row (RETURN metric, kind ALL). */
  public record SnapshotRow(LeaderboardPeriod period, LocalDate tradeDate, long accountId, long playerId, Integer rank,
                            boolean eligible, BigDecimal twr, BigDecimal pnl, BigDecimal excess,
                            BigDecimal maxDrawdown, BigDecimal equity, BigDecimal netContributions, int fills,
                            int symbolsTraded, Instant computedAt) {}

  static final String MERGE_SNAPSHOT = """
      MERGE INTO leaderboard_snapshot s
      USING (VALUES (CAST(? AS VARCHAR(8)), CAST(? AS DATE), CAST(? AS BIGINT), CAST(? AS BIGINT), CAST(? AS INT),
                     CAST(? AS BOOLEAN), CAST(? AS NUMERIC(20,10)), CAST(? AS NUMERIC(19,2)), CAST(? AS NUMERIC(20,10)),
                     CAST(? AS NUMERIC(20,10)), CAST(? AS NUMERIC(19,2)), CAST(? AS NUMERIC(19,2)), CAST(? AS INT),
                     CAST(? AS INT), CAST(? AS TIMESTAMP WITH TIME ZONE)))
        AS v(period_kind, trade_date, account_id, player_id, rank_no, eligible, twr, pnl, excess, mdd, equity, nc,
             fills, symbols, computed_at)
      ON s.period_kind = v.period_kind AND s.trade_date = v.trade_date AND s.account_id = v.account_id
      WHEN MATCHED THEN UPDATE SET player_id = v.player_id, rank_no = v.rank_no, eligible = v.eligible, twr = v.twr,
        pnl = v.pnl, excess_vs_bench = v.excess, max_drawdown = v.mdd, equity = v.equity, net_contributions = v.nc,
        fills = v.fills, symbols_traded = v.symbols, computed_at = v.computed_at
      WHEN NOT MATCHED THEN INSERT (period_kind, trade_date, account_id, player_id, rank_no, eligible, twr, pnl,
        excess_vs_bench, max_drawdown, equity, net_contributions, fills, symbols_traded, computed_at)
        VALUES (v.period_kind, v.trade_date, v.account_id, v.player_id, v.rank_no, v.eligible, v.twr, v.pnl, v.excess,
                v.mdd, v.equity, v.nc, v.fills, v.symbols, v.computed_at)""";

  private final JdbcClient jdbc;

  public LeagueRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Activity per player in {@code (fromExclusive, toInclusive]}; {@code fromExclusive} null = all time. */
  public Map<Long, Activity> activity(Instant fromExclusive, Instant toInclusive) {
    Instant from = fromExclusive == null ? Instant.EPOCH : fromExclusive;
    Map<Long, int[]> acc = new HashMap<>();
    Map<Long, BigDecimal[]> gross = new HashMap<>();
    jdbc.sql("""
            SELECT a.player_id, COUNT(*) AS fills, COUNT(DISTINCT o.symbol) AS symbols
              FROM trade_order o
              JOIN account a ON a.id = o.account_id
              JOIN (SELECT order_id, MIN(executed_at) AS first_at FROM execution GROUP BY order_id) f
                ON f.order_id = o.id
             WHERE f.first_at > ? AND f.first_at <= ?
             GROUP BY a.player_id""")
        .params(Jdbc.ts(from), Jdbc.ts(toInclusive))
        .query(rs -> {
          int[] v = acc.computeIfAbsent(rs.getLong("player_id"), k -> new int[5]);
          v[0] = rs.getInt("fills");
          v[1] = rs.getInt("symbols");
        });
    jdbc.sql("""
            SELECT a.player_id,
                   SUM(CASE WHEN e.side = 'SELL' AND e.realized_pnl IS NOT NULL THEN 1 ELSE 0 END) AS sells,
                   SUM(CASE WHEN e.side = 'SELL' AND e.realized_pnl > 0 THEN 1 ELSE 0 END) AS wins,
                   SUM(CASE WHEN o.channel IN ('REST_KEY', 'MCP_KEY', 'MCP_OAUTH') THEN 1 ELSE 0 END) AS api,
                   SUM(CASE WHEN e.side = 'SELL' AND e.realized_pnl > 0 THEN e.realized_pnl ELSE 0 END) AS profit,
                   SUM(CASE WHEN e.side = 'SELL' AND e.realized_pnl < 0 THEN -e.realized_pnl ELSE 0 END) AS loss
              FROM execution e
              JOIN account a ON a.id = e.account_id
              JOIN trade_order o ON o.id = e.order_id
             WHERE e.executed_at > ? AND e.executed_at <= ?
             GROUP BY a.player_id""")
        .params(Jdbc.ts(from), Jdbc.ts(toInclusive))
        .query(rs -> {
          int[] v = acc.computeIfAbsent(rs.getLong("player_id"), k -> new int[5]);
          v[2] = rs.getInt("sells");
          v[3] = rs.getInt("wins");
          v[4] = rs.getInt("api");
          gross.put(rs.getLong("player_id"), new BigDecimal[] {money(rs.getBigDecimal("profit")),
              money(rs.getBigDecimal("loss"))});
        });
    Map<Long, Activity> out = new HashMap<>();
    acc.forEach((player, v) -> {
      BigDecimal[] g = gross.getOrDefault(player, new BigDecimal[] {Money.ZERO, Money.ZERO});
      out.put(player, new Activity(v[0], v[1], v[2], v[3], v[4], g[0], g[1]));
    });
    return out;
  }

  /** Every APPLIED transfer, keyed by account id, oldest first. */
  public Map<Long, List<TransferRow>> appliedTransfersByAccount() {
    Map<Long, List<TransferRow>> out = new HashMap<>();
    jdbc.sql("""
            SELECT t.id, t.public_id, t.account_id, t.transfer_type, t.amount, t.is_contribution, t.unit_value,
                   t.units_delta, t.unit_series, t.rebased, t.memo, t.effective_at, t.spy_mark
              FROM cash_transfer t WHERE t.status = 'APPLIED' ORDER BY t.effective_at, t.id""")
        .query((rs, n) -> new TransferRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("account_id"),
            com.stocks.common.domain.TransferType.valueOf(rs.getString("transfer_type")), rs.getBigDecimal("amount"),
            rs.getBoolean("is_contribution"), rs.getBigDecimal("unit_value"), rs.getBigDecimal("units_delta"),
            rs.getInt("unit_series"), rs.getBoolean("rebased"), rs.getString("memo"), Jdbc.instant(rs, "effective_at"),
            rs.getBigDecimal("spy_mark")))
        .list()
        .forEach(t -> out.computeIfAbsent(t.accountId(), k -> new ArrayList<>()).add(t));
    return out;
  }

  /** Every EOD unit value, keyed by account id, oldest first (sparklines and drawdowns). */
  public Map<Long, List<SeriesPoint>> eodSeries() {
    Map<Long, List<SeriesPoint>> out = new HashMap<>();
    jdbc.sql("""
            SELECT account_id, trade_date, as_of, unit_value, unit_series, equity
              FROM equity_snapshot ORDER BY trade_date, account_id""")
        .query((rs, n) -> new SeriesPoint(rs.getLong("account_id"), Jdbc.date(rs, "trade_date"),
            Jdbc.instant(rs, "as_of"), rs.getBigDecimal("unit_value"), rs.getInt("unit_series"),
            rs.getBigDecimal("equity")))
        .list()
        .forEach(p -> out.computeIfAbsent(p.accountId(), k -> new ArrayList<>()).add(p));
    return out;
  }

  /** Account id → symbol with the largest market value in that day's {@code snapshot_holding}. */
  public Map<Long, String> topHoldings(LocalDate tradeDate) {
    Map<Long, String> out = new HashMap<>();
    Map<Long, BigDecimal> best = new HashMap<>();
    jdbc.sql("SELECT account_id, symbol, market_value FROM snapshot_holding WHERE trade_date = ? "
            + "ORDER BY account_id, symbol")
        .param(tradeDate)
        .query(rs -> {
          long account = rs.getLong("account_id");
          BigDecimal mv = rs.getBigDecimal("market_value");
          BigDecimal current = best.get(account);
          if (current == null || mv.compareTo(current) > 0) {
            best.put(account, mv);
            out.put(account, rs.getString("symbol"));
          }
        });
    return out;
  }

  /** Player id → rank of the latest {@code leaderboard_snapshot} of {@code period} strictly before {@code date}. */
  public Map<Long, Integer> previousRanks(LeaderboardPeriod period, LocalDate date) {
    Map<Long, Integer> out = new HashMap<>();
    jdbc.sql("""
            SELECT player_id, rank_no FROM leaderboard_snapshot
             WHERE period_kind = ? AND rank_no IS NOT NULL
               AND trade_date = (SELECT MAX(trade_date) FROM leaderboard_snapshot
                                  WHERE period_kind = ? AND trade_date < ?)""")
        .params(period.name(), period.name(), date)
        .query(rs -> {
          out.put(rs.getLong("player_id"), rs.getInt("rank_no"));
        });
    return out;
  }

  /** The rank-1 player of the latest {@code leaderboard_snapshot} of {@code period} before {@code date}. */
  public Long previousLeader(LeaderboardPeriod period, LocalDate date) {
    return previousRanks(period, date).entrySet().stream().filter(e -> e.getValue() == 1)
        .map(Map.Entry::getKey).findFirst().orElse(null);
  }

  /**
   * §16.8 rank history: the latest {@code limit} {@code leaderboard_snapshot} days of {@code period} up to
   * {@code toInclusive}, per player id (oldest first; a day the player had no row is skipped).
   */
  public Map<Long, List<RankRow>> rankHistories(LeaderboardPeriod period, LocalDate toInclusive, int limit) {
    List<LocalDate> dates = jdbc.sql("""
            SELECT trade_date FROM leaderboard_snapshot WHERE period_kind = ? AND trade_date <= ?
             GROUP BY trade_date ORDER BY trade_date DESC LIMIT ?""")
        .params(period.name(), toInclusive, limit)
        .query((rs, n) -> Jdbc.date(rs, 1)).list();
    Map<Long, List<RankRow>> out = new HashMap<>();
    if (dates.isEmpty()) {
      return out;
    }
    LocalDate from = dates.get(dates.size() - 1);
    record Row(LocalDate date, long player, Integer rank) {}
    List<Row> rows = jdbc.sql("""
            SELECT trade_date, player_id, rank_no FROM leaderboard_snapshot
             WHERE period_kind = ? AND trade_date >= ? AND trade_date <= ?
             ORDER BY trade_date, player_id""")
        .params(period.name(), from, toInclusive)
        .query((rs, n) -> new Row(Jdbc.date(rs, "trade_date"), rs.getLong("player_id"), Jdbc.intOrNull(rs, "rank_no")))
        .list();
    Map<LocalDate, Integer> ranked = new HashMap<>();
    for (Row r : rows) {
      ranked.merge(r.date(), r.rank() == null ? 0 : 1, Integer::sum);
    }
    for (Row r : rows) {
      out.computeIfAbsent(r.player(), k -> new ArrayList<>()).add(new RankRow(r.date(), r.rank(),
          ranked.getOrDefault(r.date(), 0)));
    }
    return out;
  }

  private static BigDecimal money(BigDecimal v) {
    return v == null ? Money.ZERO : Money.cents(v);
  }

  public void upsertSnapshot(SnapshotRow r) {
    jdbc.sql(MERGE_SNAPSHOT)
        .params(r.period().name(), r.tradeDate(), r.accountId(), r.playerId(), r.rank(), r.eligible(), r.twr(),
            r.pnl(), r.excess(), r.maxDrawdown(), r.equity(), r.netContributions(), r.fills(), r.symbolsTraded(),
            Jdbc.ts(r.computedAt()))
        .update();
  }

  /** Rows of one period and day, by rank (unranked last). */
  public List<SnapshotRow> snapshots(LeaderboardPeriod period, LocalDate date) {
    return jdbc.sql("""
            SELECT period_kind, trade_date, account_id, player_id, rank_no, eligible, twr, pnl, excess_vs_bench,
                   max_drawdown, equity, net_contributions, fills, symbols_traded, computed_at
              FROM leaderboard_snapshot WHERE period_kind = ? AND trade_date = ?
             ORDER BY rank_no, account_id""")
        .params(period.name(), date)
        .query((rs, n) -> new SnapshotRow(LeaderboardPeriod.valueOf(rs.getString("period_kind")),
            Jdbc.date(rs, "trade_date"), rs.getLong("account_id"), rs.getLong("player_id"),
            Jdbc.intOrNull(rs, "rank_no"), rs.getBoolean("eligible"), rs.getBigDecimal("twr"), rs.getBigDecimal("pnl"),
            rs.getBigDecimal("excess_vs_bench"), rs.getBigDecimal("max_drawdown"), rs.getBigDecimal("equity"),
            rs.getBigDecimal("net_contributions"), rs.getInt("fills"), rs.getInt("symbols_traded"),
            Jdbc.instant(rs, "computed_at")))
        .list();
  }
}
