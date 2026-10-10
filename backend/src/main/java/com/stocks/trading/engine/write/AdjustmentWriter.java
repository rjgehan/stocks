package com.stocks.trading.engine.write;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.trading.engine.EngineThread;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The only writer of {@code position_adjustment} (append-only, §8.1, §14): one row per lot a corporate action changed,
 * with the exact quantity, cost and symbol before and after. {@code HoldingsReconstructor} (§16.4) and invariant I5
 * replay these rows, so every non-trade change to a lot must be recorded here. Engine thread only.
 */
@Component
public class AdjustmentWriter {

  /** The columns of one row. {@code cilLedgerIdOrNull}: the CASH_IN_LIEU row on the lot that absorbed it. */
  public record NewAdjustment(long accountId, long corporateActionId, long lotId, String symbolBefore,
                              String symbolAfter, BigDecimal qtyBefore, BigDecimal qtyAfter, BigDecimal costBefore,
                              BigDecimal costAfter, Long cilLedgerIdOrNull, Instant effectiveAt) {}

  private final JdbcClient jdbc;

  public AdjustmentWriter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(NewAdjustment a) {
    EngineThread.assertCurrent();
    jdbc.sql("""
            INSERT INTO position_adjustment (account_id, corporate_action_id, lot_id, symbol_before, symbol_after,
                                             qty_before, qty_after, cost_before, cost_after, cil_ledger_id,
                                             effective_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(a.accountId(), a.corporateActionId(), a.lotId(), a.symbolBefore(), a.symbolAfter(), a.qtyBefore(),
            a.qtyAfter(), a.costBefore(), a.costAfter(), new SqlParameterValue(Types.BIGINT, a.cilLedgerIdOrNull()),
            Jdbc.ts(a.effectiveAt()))
        .update();
  }
}
