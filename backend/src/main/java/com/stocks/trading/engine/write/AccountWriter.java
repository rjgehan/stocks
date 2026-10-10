package com.stocks.trading.engine.write;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.trading.engine.EngineThread;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * Writes new {@code account} rows (runs) and their state columns ({@code status}, {@code restriction*},
 * {@code final_*}); the money caches belong to {@link LedgerWriter} and {@link TransferWriter}. Engine thread only.
 */
@Component
public class AccountWriter {

  private final JdbcClient jdbc;

  public AccountWriter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A new ACTIVE run with zero cash; its INITIAL transfer funds it. Returns the id. */
  public long insert(String publicId, long playerId, int runNo, BigDecimal startingCash, int unitSeries,
                     Instant openedAt, String resetIdempotencyKey) {
    EngineThread.assertCurrent();
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.sql("""
            INSERT INTO account (public_id, player_id, run_no, account_type, status, starting_cash, unit_series,
                                 opened_at, reset_idempotency_key)
            VALUES (?, ?, ?, 'CASH', 'ACTIVE', ?, ?, ?, ?)""")
        .params(publicId, playerId, runNo, startingCash, unitSeries, Jdbc.ts(openedAt),
            new SqlParameterValue(Types.VARCHAR, resetIdempotencyKey))
        .update(keys, "id");
    return keys.getKeyAs(Long.class);
  }

  /** Closes a run (§10.5 reset), recording its final equity and unit value (§16.3). */
  public void close(long accountId, Instant closedAt, String closeReason, BigDecimal finalEquity,
                    BigDecimal finalUnitValue) {
    EngineThread.assertCurrent();
    jdbc.sql("""
            UPDATE account SET status = 'CLOSED', closed_at = ?, close_reason = ?, final_equity = ?,
                   final_unit_value = ?, version = version + 1
             WHERE id = ?""")
        .params(Jdbc.ts(closedAt), closeReason, finalEquity, finalUnitValue, accountId).update();
  }

  /** A reset before the payable date voids the run's unpaid dividends (§14, §10.5). */
  public void voidAccruedDividends(long accountId, Instant at) {
    EngineThread.assertCurrent();
    jdbc.sql("UPDATE dividend_entitlement SET status = 'VOID', voided_at = ? WHERE account_id = ? AND status = 'ACCRUED'")
        .params(Jdbc.ts(at), accountId).update();
  }

  public void setRestriction(long accountId, AccountRestriction restriction, Instant untilOrNull, String reason) {
    EngineThread.assertCurrent();
    jdbc.sql("""
            UPDATE account SET restriction = ?, restriction_until = ?, restriction_reason = ?, version = version + 1
             WHERE id = ?""")
        .params(restriction.name(), Jdbc.tsParam(untilOrNull),
            new SqlParameterValue(Types.VARCHAR, reason == null ? null : reason.length() > 80
                ? reason.substring(0, 80) : reason),
            accountId)
        .update();
  }
}
