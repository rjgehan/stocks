package com.stocks.trading.engine.write;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.trading.engine.EngineThread;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * Writes {@code dividend_entitlement} (§8.1, §14): ACCRUED on the ex-date, PAID with {@code paid_at} = the DIVIDEND
 * ledger row's {@code posted_at}; voiding on reset is {@link AccountWriter#voidAccruedDividends}. Also the
 * {@code daily_mark.dividend_ex} column the SPY benchmark reads (§16.6), which belongs to WS3. Engine thread only.
 */
@Component
public class DividendWriter {

  private final JdbcClient jdbc;

  public DividendWriter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A new ACCRUED entitlement; returns its id. */
  public long accrue(long corporateActionId, long accountId, String symbol, BigDecimal eligibleQty, BigDecimal amount,
                     LocalDate payableDate, Instant accruedAt) {
    EngineThread.assertCurrent();
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.sql("""
            INSERT INTO dividend_entitlement (corporate_action_id, account_id, symbol, eligible_qty, amount,
                                              payable_date, status, accrued_at)
            VALUES (?, ?, ?, ?, ?, ?, 'ACCRUED', ?)""")
        .params(corporateActionId, accountId, symbol, eligibleQty, amount, payableDate, Jdbc.ts(accruedAt))
        .update(keys, "id");
    return keys.getKeyAs(Long.class);
  }

  /** ACCRUED → PAID; {@code paidAt} must equal the DIVIDEND ledger row's {@code posted_at} (§16.1). */
  public boolean markPaid(long entitlementId, Instant paidAt) {
    EngineThread.assertCurrent();
    return jdbc.sql("UPDATE dividend_entitlement SET status = 'PAID', paid_at = ? WHERE id = ? AND status = 'ACCRUED'")
        .params(Jdbc.ts(paidAt), entitlementId).update() == 1;
  }

  /** ACCRUED → VOID (an entitlement whose run closed before it was paid). */
  public boolean markVoid(long entitlementId, Instant voidedAt) {
    EngineThread.assertCurrent();
    return jdbc.sql("UPDATE dividend_entitlement SET status = 'VOID', voided_at = ? WHERE id = ? AND status = 'ACCRUED'")
        .params(Jdbc.ts(voidedAt), entitlementId).update() == 1;
  }

  /**
   * Adds a cash dividend with ex-date {@code exDate} to {@code daily_mark.dividend_ex} of {@code symbol} (creating a
   * PROVISIONAL row without marks if the day has none yet; WS2's day-end jobs fill the marks later and never touch
   * this column). Every parameter is cast, so the MERGE runs on H2 and PostgreSQL alike (§8).
   */
  public void addDividendEx(String symbol, LocalDate exDate, BigDecimal perShare, Instant now) {
    EngineThread.assertCurrent();
    jdbc.sql("""
            MERGE INTO daily_mark d
            USING (VALUES (CAST(? AS VARCHAR(12)), CAST(? AS DATE), CAST(? AS NUMERIC(19,6)),
                           CAST(? AS TIMESTAMP WITH TIME ZONE)))
                  AS v(symbol, trade_date, dividend_ex, updated_at)
            ON d.symbol = v.symbol AND d.trade_date = v.trade_date
            WHEN MATCHED THEN UPDATE SET dividend_ex = d.dividend_ex + v.dividend_ex
            WHEN NOT MATCHED THEN INSERT (symbol, trade_date, dividend_ex, quality, updated_at)
                 VALUES (v.symbol, v.trade_date, v.dividend_ex, 'PROVISIONAL', v.updated_at)""")
        .params(symbol, exDate, perShare.setScale(6, java.math.RoundingMode.HALF_EVEN), Jdbc.ts(now))
        .update();
  }
}
