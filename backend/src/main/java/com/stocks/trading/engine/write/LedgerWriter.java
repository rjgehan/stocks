package com.stocks.trading.engine.write;

import com.stocks.common.domain.LedgerEntryType;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.trading.engine.EngineThread;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * The only writer of {@code cash_ledger} (append-only, single-entry, D5) and of {@code account.cash_balance}, which it
 * updates in the same call, so the cache can never drift from the ledger (I1, I2). Balances are never edited
 * directly: every cash change is one ledger row. Engine thread only.
 */
@Component
public class LedgerWriter {

  /** A posted row. */
  public record Posted(long id, String publicId, BigDecimal balanceAfter) {}

  private final JdbcClient jdbc;
  private final PublicId publicIds;

  public LedgerWriter(JdbcClient jdbc, PublicId publicIds) {
    this.jdbc = jdbc;
    this.publicIds = publicIds;
  }

  /**
   * Appends a ledger row and moves {@code account.cash_balance} by {@code amount}.
   *
   * @param idempotencyKey {@code XFER:<xfr>}, {@code EXEC:<exe>}, … (UNIQUE: a second post of the same source fails)
   * @throws IllegalStateException if a CASH account would go negative (the callers' guards make this a bug)
   */
  public Posted append(long accountId, LedgerEntryType type, BigDecimal amount, LocalDate tradeDate,
                       LocalDate settleDate, Instant postedAt, Long transferId, Long executionId, String symbol,
                       String memo, String idempotencyKey) {
    EngineThread.assertCurrent();
    BigDecimal cash = jdbc.sql("SELECT cash_balance FROM account WHERE id = ?").param(accountId)
        .query(BigDecimal.class).single();
    BigDecimal after = cash.add(amount);
    if (after.signum() < 0) {
      throw new IllegalStateException("Cash of account " + accountId + " would go negative: " + cash + " + " + amount);
    }
    String publicId = publicIds.next(Prefix.LEDGER);
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.sql("""
            INSERT INTO cash_ledger (public_id, account_id, entry_type, amount, balance_after, trade_date, settle_date,
                                     posted_at, transfer_id, execution_id, symbol, memo, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(publicId, accountId, type.name(), amount, after, tradeDate, settleDate, Jdbc.ts(postedAt),
            new SqlParameterValue(Types.BIGINT, transferId), new SqlParameterValue(Types.BIGINT, executionId),
            new SqlParameterValue(Types.VARCHAR, symbol), new SqlParameterValue(Types.VARCHAR, memo),
            idempotencyKey)
        .update(keys, "id");
    jdbc.sql("UPDATE account SET cash_balance = ?, version = version + 1 WHERE id = ?").params(after, accountId)
        .update();
    return new Posted(keys.getKeyAs(Long.class), publicId, after);
  }
}
