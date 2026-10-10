package com.stocks.trading.engine.write;

import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.Side;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.trading.engine.EngineThread;
import com.stocks.trading.fees.Fees;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/** The only writer of {@code execution} (append-only, §8.1); engine thread only. */
@Component
public class ExecutionWriter {

  public record NewExecution(String publicId, long orderId, long accountId, String symbol, Side side, BigDecimal qty,
                             BigDecimal price, BigDecimal principal, Fees fees, BigDecimal netAmount,
                             BigDecimal realizedPnl, Liquidity liquidity, Instant executedAt, LocalDate tradeDate,
                             LocalDate settleDate, ExecQuote ref) {}

  private final JdbcClient jdbc;

  public ExecutionWriter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts an execution with its reference quote (§12.3); returns its id. */
  public long insert(NewExecution e) {
    EngineThread.assertCurrent();
    ExecQuote q = e.ref();
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.sql("""
            INSERT INTO execution (public_id, order_id, account_id, symbol, side, qty, price, principal, fee_sec,
                                   fee_taf, fee_cat, net_amount, realized_pnl, liquidity, executed_at, trade_date,
                                   settle_date, ref_bid, ref_ask, ref_bid_size, ref_ask_size, ref_last, ref_quote_ts,
                                   ref_confirmed_at, ref_source, ref_quality, ref_clamped)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(e.publicId(), e.orderId(), e.accountId(), e.symbol(), e.side().name(), e.qty(), e.price(),
            e.principal(), e.fees().sec(), e.fees().taf(), e.fees().cat(), e.netAmount(),
            Jdbc.decimalParam(e.realizedPnl()), e.liquidity().name(), Jdbc.ts(e.executedAt()), e.tradeDate(),
            e.settleDate(), Jdbc.decimalParam(q.bid()), Jdbc.decimalParam(q.ask()),
            new SqlParameterValue(Types.BIGINT, q.bidSize()), new SqlParameterValue(Types.BIGINT, q.askSize()),
            Jdbc.decimalParam(q.last()), Jdbc.tsParam(q.quoteTs()), Jdbc.tsParam(q.confirmedAt()),
            q.source() == null ? "unknown" : q.source(), q.quality().name(), q.clamped())
        .update(keys, "id");
    return keys.getKeyAs(Long.class);
  }
}
