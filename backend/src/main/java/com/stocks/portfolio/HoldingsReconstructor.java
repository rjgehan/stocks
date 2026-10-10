package com.stocks.portfolio;

import com.stocks.common.jdbc.Jdbc;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Holdings of an account at a past instant (§16.4), by <b>forward replay</b>: per symbol, the signed execution
 * quantities with {@code executed_at ≤ t}, plus every {@code position_adjustment} with {@code effective_at ≤ t}
 * applied as "remove {@code qty_before} of {@code symbol_before}, add {@code qty_after} of {@code symbol_after}". That
 * one rule covers splits, dust removal and name changes exactly, because each of them is recorded per lot; nothing is
 * undone, so an EOD catch-up after a 03:45 split still reconstructs the pre-split holdings. Also the per-symbol cost
 * of the shares bought (for an average-cost fallback mark).
 */
@Component
public class HoldingsReconstructor {

  /** One reconstructed holding: quantity and the remaining cost (FIFO-free approximation: Σ buy cost pro rata). */
  public record Holding(String symbol, BigDecimal qty, BigDecimal costBasis) {}

  private final JdbcClient jdbc;

  public HoldingsReconstructor(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Symbol → holding with a positive quantity at {@code t}, sorted by symbol. */
  public Map<String, Holding> holdingsAt(long accountId, Instant t) {
    Map<String, BigDecimal> qty = new TreeMap<>();
    Map<String, BigDecimal> buyQty = new TreeMap<>();
    Map<String, BigDecimal> buyCost = new TreeMap<>();
    jdbc.sql("""
            SELECT symbol, side, SUM(qty) AS qty, SUM(net_amount) AS net
              FROM execution
             WHERE account_id = ? AND executed_at <= ?
             GROUP BY symbol, side""")
        .params(accountId, Jdbc.ts(t))
        .query(rs -> {
          String symbol = rs.getString("symbol");
          BigDecimal q = rs.getBigDecimal("qty");
          boolean buy = "BUY".equals(rs.getString("side"));
          qty.merge(symbol, buy ? q : q.negate(), BigDecimal::add);
          if (buy) {
            buyQty.merge(symbol, q, BigDecimal::add);
            buyCost.merge(symbol, rs.getBigDecimal("net").negate(), BigDecimal::add);
          }
        });
    jdbc.sql("""
            SELECT symbol_before, symbol_after, qty_before, qty_after
              FROM position_adjustment
             WHERE account_id = ? AND effective_at <= ?
             ORDER BY effective_at, id""")
        .params(accountId, Jdbc.ts(t))
        .query(rs -> {
          String before = rs.getString("symbol_before");
          String after = rs.getString("symbol_after");
          qty.merge(before, rs.getBigDecimal("qty_before").negate(), BigDecimal::add);
          qty.merge(after, rs.getBigDecimal("qty_after"), BigDecimal::add);
          if (!before.equals(after)) {
            buyQty.merge(after, buyQty.getOrDefault(before, BigDecimal.ZERO), BigDecimal::add);
            buyCost.merge(after, buyCost.getOrDefault(before, BigDecimal.ZERO), BigDecimal::add);
          }
        });
    Map<String, Holding> out = new TreeMap<>();
    qty.forEach((symbol, q) -> {
      if (q.signum() > 0) {
        BigDecimal bq = buyQty.getOrDefault(symbol, BigDecimal.ZERO);
        BigDecimal cost = bq.signum() > 0
            ? buyCost.getOrDefault(symbol, BigDecimal.ZERO).multiply(q).divide(bq, 2, java.math.RoundingMode.HALF_EVEN)
            : null;
        out.put(symbol, new Holding(symbol, q, cost));
      }
    });
    return out;
  }
}
