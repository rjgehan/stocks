package com.stocks.trading.engine.write;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.trading.engine.EngineThread;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The only writer of {@code position} and {@code position_lot} (§4 rule 1). Positions are kept equal to the sum of
 * their open lots (I5); a position row is deleted when its quantity reaches 0. Engine thread only.
 */
@Component
public class PositionWriter {

  private final JdbcClient jdbc;

  public PositionWriter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A new FIFO lot for a BUY execution, with its GFV funding attribution (§10.4). */
  public void openLot(long accountId, String symbol, long executionId, LocalDate tradeDate, Instant openedAt,
                      BigDecimal qty, BigDecimal cost, BigDecimal unsettledFunding, LocalDate fundingSettleDate) {
    EngineThread.assertCurrent();
    jdbc.sql("""
            INSERT INTO position_lot (account_id, symbol, open_execution_id, trade_date, opened_at, qty_original,
                                      qty_remaining, cost_remaining, unsettled_funding, funding_settle_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(accountId, symbol, executionId, tradeDate, Jdbc.ts(openedAt), qty, qty, cost, unsettledFunding,
            Jdbc.dateParam(fundingSettleDate))
        .update();
  }

  /** Relieves part or all of a lot; a lot whose quantity reaches 0 is closed. */
  public void relieveLot(long lotId, BigDecimal qtyRemaining, BigDecimal costRemaining, Instant at) {
    EngineThread.assertCurrent();
    boolean closed = qtyRemaining.signum() == 0;
    jdbc.sql("UPDATE position_lot SET qty_remaining = ?, cost_remaining = ?, closed_at = ? WHERE id = ?")
        .params(qtyRemaining, costRemaining, Jdbc.tsParam(closed ? at : null), lotId).update();
  }

  /** Adds a BUY to the position (insert or update). */
  public void add(long accountId, String symbol, BigDecimal qty, BigDecimal cost, Instant at) {
    EngineThread.assertCurrent();
    int updated = jdbc.sql("""
            UPDATE position SET qty = qty + ?, cost_basis = cost_basis + ?, updated_at = ?, version = version + 1
             WHERE account_id = ? AND symbol = ?""")
        .params(qty, cost, Jdbc.ts(at), accountId, symbol).update();
    if (updated == 0) {
      jdbc.sql("INSERT INTO position (account_id, symbol, qty, cost_basis, opened_at, updated_at)"
              + " VALUES (?, ?, ?, ?, ?, ?)")
          .params(accountId, symbol, qty, cost, Jdbc.ts(at), Jdbc.ts(at)).update();
    }
  }

  /** Removes a SELL from the position; deletes the row when the quantity reaches 0. */
  public void reduce(long accountId, String symbol, BigDecimal newQty, BigDecimal newCost, Instant at) {
    EngineThread.assertCurrent();
    if (newQty.signum() == 0) {
      jdbc.sql("DELETE FROM position WHERE account_id = ? AND symbol = ?").params(accountId, symbol).update();
      return;
    }
    jdbc.sql("""
            UPDATE position SET qty = ?, cost_basis = ?, updated_at = ?, version = version + 1
             WHERE account_id = ? AND symbol = ?""")
        .params(newQty, newCost, Jdbc.ts(at), accountId, symbol).update();
  }

  // ------------------------------------------------------------------------------------------- corporate actions (§14)

  /** A split's new lot state (quantities at scale 5, cost in cents); a lot whose quantity reaches 0 is closed. */
  public void splitLot(long lotId, BigDecimal qtyOriginal, BigDecimal qtyRemaining, BigDecimal costRemaining,
                       Instant at) {
    EngineThread.assertCurrent();
    boolean closed = qtyRemaining.signum() == 0;
    jdbc.sql("""
            UPDATE position_lot SET qty_original = ?, qty_remaining = ?, cost_remaining = ?,
                   closed_at = COALESCE(?, closed_at)
             WHERE id = ?""")
        .params(qtyOriginal, qtyRemaining, costRemaining, Jdbc.tsParam(closed ? at : null), lotId).update();
  }

  /** Sets a position to Σ of its open lots after a corporate action; deletes the row when the quantity is 0. */
  public void setPosition(long accountId, String symbol, BigDecimal qty, BigDecimal cost, Instant at) {
    reduce(accountId, symbol, qty, cost, at);
  }

  /**
   * NAME_CHANGE: moves the open lots and the position of {@code oldSymbol} to {@code newSymbol}. If the account
   * already holds {@code newSymbol}, the two positions merge (quantities and cost add up).
   */
  public void renamePosition(long accountId, String oldSymbol, String newSymbol, Instant at) {
    EngineThread.assertCurrent();
    jdbc.sql("UPDATE position_lot SET symbol = ? WHERE account_id = ? AND symbol = ? AND closed_at IS NULL")
        .params(newSymbol, accountId, oldSymbol).update();
    var old = jdbc.sql("SELECT qty, cost_basis FROM position WHERE account_id = ? AND symbol = ?")
        .params(accountId, oldSymbol)
        .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("qty"), rs.getBigDecimal("cost_basis")}).optional();
    if (old.isEmpty()) {
      return;
    }
    int merged = jdbc.sql("""
            UPDATE position SET qty = qty + ?, cost_basis = cost_basis + ?, updated_at = ?, version = version + 1
             WHERE account_id = ? AND symbol = ?""")
        .params(old.get()[0], old.get()[1], Jdbc.ts(at), accountId, newSymbol).update();
    if (merged > 0) {
      jdbc.sql("DELETE FROM position WHERE account_id = ? AND symbol = ?").params(accountId, oldSymbol).update();
    } else {
      jdbc.sql("""
              UPDATE position SET symbol = ?, updated_at = ?, version = version + 1
               WHERE account_id = ? AND symbol = ?""")
          .params(newSymbol, Jdbc.ts(at), accountId, oldSymbol).update();
    }
  }
}
