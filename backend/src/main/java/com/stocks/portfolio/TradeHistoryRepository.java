package com.stocks.portfolio;

import com.stocks.common.domain.Side;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.portfolio.ClosedLots.Adjustment;
import com.stocks.portfolio.ClosedLots.Event;
import com.stocks.portfolio.ClosedLots.Execution;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The inputs of the §16.8 closed-lot replay ({@link ClosedLots}), read-only on WS3's tables: every execution of the
 * given accounts and every {@code position_adjustment}, keyed by its lot's {@code open_execution_id}.
 */
@Repository
public class TradeHistoryRepository {

  private final JdbcClient jdbc;

  public TradeHistoryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Replay events per account id (unsorted; {@link ClosedLots#replay} orders them). */
  public Map<Long, List<Event>> events(Collection<Long> accountIds) {
    Map<Long, List<Event>> out = new HashMap<>();
    if (accountIds.isEmpty()) {
      return out;
    }
    String in = accountIds.stream().map(id -> "?").collect(Collectors.joining(", "));
    List<Object> ids = new ArrayList<>(accountIds);
    jdbc.sql("SELECT id, account_id, symbol, side, qty, net_amount, realized_pnl, executed_at FROM execution "
            + "WHERE account_id IN (" + in + ")")
        .params(ids)
        .query(rs -> {
          out.computeIfAbsent(rs.getLong("account_id"), k -> new ArrayList<>()).add(new Execution(rs.getLong("id"),
              rs.getString("symbol"), Side.valueOf(rs.getString("side")), rs.getBigDecimal("qty"),
              rs.getBigDecimal("net_amount"), rs.getBigDecimal("realized_pnl"), Jdbc.instant(rs, "executed_at")));
        });
    jdbc.sql("SELECT pa.id, pa.account_id, pl.open_execution_id, pa.symbol_after, pa.qty_after, pa.cost_after, "
            + "pa.effective_at FROM position_adjustment pa JOIN position_lot pl ON pl.id = pa.lot_id "
            + "WHERE pa.account_id IN (" + in + ")")
        .params(ids)
        .query(rs -> {
          out.computeIfAbsent(rs.getLong("account_id"), k -> new ArrayList<>()).add(new Adjustment(rs.getLong("id"),
              rs.getLong("open_execution_id"), rs.getString("symbol_after"), rs.getBigDecimal("qty_after"),
              rs.getBigDecimal("cost_after"), Jdbc.instant(rs, "effective_at")));
        });
    return out;
  }

  /** Every closed lot of the given accounts (each account replayed on its own), in closing order. */
  public List<ClosedLots.Lot> closedLots(Collection<Long> accountIds) {
    List<ClosedLots.Lot> out = new ArrayList<>();
    events(accountIds).values().forEach(ev -> out.addAll(ClosedLots.replay(ev)));
    out.sort(java.util.Comparator.comparing(ClosedLots.Lot::closedAt).thenComparingLong(ClosedLots.Lot::sellExecutionId)
        .thenComparingLong(ClosedLots.Lot::buyExecutionId));
    return out;
  }
}
