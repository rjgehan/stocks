package com.stocks.trading.engine.write;

import com.stocks.common.domain.ViolationType;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.trading.engine.EngineThread;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The only writer of {@code account_violation} (append-only, §10.4). Engine thread only. */
@Component
public class ViolationWriter {

  private final JdbcClient jdbc;

  public ViolationWriter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(String publicId, long accountId, ViolationType type, long orderId, long executionId,
                     String symbol, BigDecimal unsettledAmount, LocalDate fundingSettleDate, LocalDate tradeDate,
                     Instant occurredAt, Instant expiresAt, String detail) {
    EngineThread.assertCurrent();
    jdbc.sql("""
            INSERT INTO account_violation (public_id, account_id, violation_type, order_id, execution_id, symbol,
                                           unsettled_amount, funding_settle_date, trade_date, occurred_at, expires_at,
                                           detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(publicId, accountId, type.name(), orderId, executionId, symbol, unsettledAmount, fundingSettleDate,
            tradeDate, Jdbc.ts(occurredAt), Jdbc.ts(expiresAt), new SqlParameterValue(Types.VARCHAR, detail))
        .update();
  }
}
