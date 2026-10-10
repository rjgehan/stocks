package com.stocks.trading.account;

import com.stocks.common.domain.TransferStatus;
import com.stocks.common.domain.TransferType;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.trading.CashTransferCommand;
import com.stocks.contract.trading.CashTransferService;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.TransferFilter;
import com.stocks.contract.trading.dto.CashTransferPreviewDto;
import com.stocks.contract.trading.dto.CashTransferPreviewDto.TransferPreviewAccountDto;
import com.stocks.contract.trading.dto.TransferBatchResultDto;
import com.stocks.contract.trading.dto.TransferRowDto;
import com.stocks.common.money.Money;
import com.stocks.trading.engine.EngineCalls;
import com.stocks.trading.engine.EngineCommand;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.store.IdempotencyStore;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Admin cash transfers (§21.2): {@link #preview} runs the plan read-only; {@link #apply} is one engine command per
 * batch, idempotent through the {@code Idempotency-Key} (a stored response is replayed without enqueuing anything).
 * With {@code allOrNothing}, a batch where any account would be skipped is rolled back and answers
 * {@code 422 INSUFFICIENT_WITHDRAWABLE}, listing the accounts in {@code fields}.
 */
@Service
public class CashTransferServiceImpl implements CashTransferService {

  private final TransferPlanner planner;
  private final EngineCalls engine;
  private final IdempotencyStore idempotency;
  private final TradingClock clock;
  private final JdbcClient jdbc;
  private final JsonMapper json;

  public CashTransferServiceImpl(TransferPlanner planner, EngineCalls engine, IdempotencyStore idempotency,
                                 TradingClock clock, JdbcClient jdbc, JsonMapper json) {
    this.planner = planner;
    this.engine = engine;
    this.idempotency = idempotency;
    this.clock = clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public CashTransferPreviewDto preview(CashTransferCommand raw) {
    CashTransferCommand cmd = TransferPlanner.validate(raw);
    Instant now = clock.now();
    TransferPlanner.Plan plan = planner.plan(cmd, now, clock.session(now).type());
    List<TransferPreviewAccountDto> rows = new ArrayList<>();
    for (TransferPlanner.Planned p : plan.accounts()) {
      rows.add(new TransferPreviewAccountDto(p.player().handle(), p.account().publicId(), p.account().cashBalance(),
          p.balances().withdrawable(), p.valuation().equity().setScale(Money.SCALE, RoundingMode.HALF_EVEN),
          p.unitValue(), p.marksQuality(), p.worstMarkBasis(), p.wouldApply(), p.skipReason(), p.warnings()));
    }
    return new CashTransferPreviewDto(now, plan.session(), rows);
  }

  @Override
  public TransferBatchResultDto apply(CashTransferCommand cmd, IdempotencyRef idem, long adminPlayerId) {
    TransferPlanner.validate(cmd);
    if (idem != null) {
      var stored = idempotency.find(idem.scope(), idem.key());
      if (stored.isPresent() && stored.get().requestHash().equals(idem.requestHash())) {
        return json.readValue(stored.get().responseBody(), TransferBatchResultDto.class);   // fast path (§17.6)
      }
    }
    return engine.call(new EngineCommand.ApplyCashTransfersCmd(cmd, idem, adminPlayerId));
  }

  @Override
  public Page<TransferRowDto> list(TransferFilter filter, Cursor cursor, int limit) {
    int n = Page.clampLimit(limit);
    StringBuilder sql = new StringBuilder("""
        SELECT t.id, t.public_id, b.public_id AS batch_public_id, p.handle, a.public_id AS account_public_id,
               t.transfer_type, t.amount, t.is_contribution, t.status, t.skip_reason, t.memo, t.effective_at,
               c.handle AS by_handle, t.equity_before, t.unit_value
          FROM cash_transfer t
          JOIN transfer_batch b ON b.id = t.batch_id
          JOIN account a ON a.id = t.account_id
          JOIN player p ON p.id = a.player_id
          LEFT JOIN player c ON c.id = t.created_by
         WHERE 1 = 1""");
    List<Object> params = new ArrayList<>();
    if (filter != null && filter.handleOrNull() != null) {
      sql.append(" AND p.handle = ?");
      params.add(filter.handleOrNull().trim().toLowerCase(java.util.Locale.ROOT));
    }
    if (filter != null && filter.batchIdOrNull() != null) {
      sql.append(" AND b.public_id = ?");
      params.add(filter.batchIdOrNull());
    }
    if (filter != null && filter.fromOrNull() != null) {
      sql.append(" AND t.effective_at >= ?");
      params.add(Jdbc.ts(filter.fromOrNull()));
    }
    if (filter != null && filter.toOrNull() != null) {
      sql.append(" AND t.effective_at < ?");
      params.add(Jdbc.ts(filter.toOrNull()));
    }
    if (cursor != null) {
      sql.append(" AND (t.effective_at < ? OR (t.effective_at = ? AND t.id < ?))");
      params.add(Jdbc.ts(cursor.at()));
      params.add(Jdbc.ts(cursor.at()));
      params.add(cursor.id());
    }
    sql.append(" ORDER BY t.effective_at DESC, t.id DESC LIMIT ?");
    params.add(n + 1);
    record Row(long id, Instant at, TransferRowDto dto) {}
    List<Row> rows = jdbc.sql(sql.toString()).params(params).query((rs, i) -> {
      Instant at = Jdbc.instant(rs, "effective_at");
      return new Row(rs.getLong("id"), at, new TransferRowDto(rs.getString("public_id"),
          rs.getString("batch_public_id"), rs.getString("handle"), rs.getString("account_public_id"),
          TransferType.valueOf(rs.getString("transfer_type")), rs.getBigDecimal("amount"),
          rs.getBoolean("is_contribution"), TransferStatus.valueOf(rs.getString("status")),
          rs.getString("skip_reason"), rs.getString("memo"), at, rs.getString("by_handle"),
          rs.getBigDecimal("equity_before"), rs.getBigDecimal("unit_value")));
    }).list();
    return Page.fromLookahead(rows, n, r -> Cursor.of(r.at(), r.id())).map(Row::dto);
  }
}
