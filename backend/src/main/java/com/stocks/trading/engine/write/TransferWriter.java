package com.stocks.trading.engine.write;

import com.stocks.common.domain.AdjustmentTreatment;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.TransferScope;
import com.stocks.common.domain.TransferStatus;
import com.stocks.common.domain.TransferType;
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
 * The only writer of {@code transfer_batch}, {@code cash_transfer} (append-only) and the flow caches of
 * {@code account} ({@code net_contributions}, {@code units}, {@code unit_series}; I3, I4). Engine thread only.
 */
@Component
public class TransferWriter {

  public record NewBatch(String publicId, TransferScope scope, TransferType type, BigDecimal amount,
                         AdjustmentTreatment treatAs, String memo, String kindsFilter, boolean allOrNothing,
                         String idempotencyKey, Long requestedBy, Instant createdAt) {}

  public record NewTransfer(String publicId, long batchId, long accountId, TransferType type, BigDecimal amount,
                            boolean contribution, TransferStatus status, String skipReason, String memo,
                            BigDecimal equityBefore, BigDecimal unitValue, BigDecimal unitsDelta, int unitSeries,
                            boolean rebased, DataQuality marksQuality, BigDecimal spyMark, Instant effectiveAt,
                            Long createdBy) {}

  private final JdbcClient jdbc;

  public TransferWriter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long insertBatch(NewBatch b) {
    EngineThread.assertCurrent();
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.sql("""
            INSERT INTO transfer_batch (public_id, scope, transfer_type, amount, treat_as, memo, kinds_filter,
                                        all_or_nothing, idempotency_key, requested_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(b.publicId(), b.scope().name(), b.type().name(), b.amount(), b.treatAs().name(), b.memo(),
            new SqlParameterValue(Types.VARCHAR, b.kindsFilter()), b.allOrNothing(),
            new SqlParameterValue(Types.VARCHAR, b.idempotencyKey()),
            new SqlParameterValue(Types.BIGINT, b.requestedBy()), Jdbc.ts(b.createdAt()))
        .update(keys, "id");
    return keys.getKeyAs(Long.class);
  }

  public void setBatchCounts(long batchId, int applied, int skipped) {
    EngineThread.assertCurrent();
    jdbc.sql("UPDATE transfer_batch SET applied_count = ?, skipped_count = ? WHERE id = ?")
        .params(applied, skipped, batchId).update();
  }

  public long insertTransfer(NewTransfer t) {
    EngineThread.assertCurrent();
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.sql("""
            INSERT INTO cash_transfer (public_id, batch_id, account_id, transfer_type, amount, is_contribution, status,
                                       skip_reason, memo, equity_before, unit_value, units_delta, unit_series,
                                       rebased, marks_quality, spy_mark, effective_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(t.publicId(), t.batchId(), t.accountId(), t.type().name(), t.amount(), t.contribution(),
            t.status().name(), new SqlParameterValue(Types.VARCHAR, t.skipReason()), t.memo(),
            Jdbc.decimalParam(t.equityBefore()), Jdbc.decimalParam(t.unitValue()), t.unitsDelta(), t.unitSeries(),
            t.rebased(), new SqlParameterValue(Types.VARCHAR, t.marksQuality() == null ? null : t.marksQuality().name()),
            Jdbc.decimalParam(t.spyMark()), Jdbc.ts(t.effectiveAt()), new SqlParameterValue(Types.BIGINT, t.createdBy()))
        .update(keys, "id");
    return keys.getKeyAs(Long.class);
  }

  /** Moves the flow caches of an account (I3, I4) after an APPLIED transfer. */
  public void applyFlow(long accountId, BigDecimal netContributionsDelta, BigDecimal unitsDelta, int unitSeries) {
    EngineThread.assertCurrent();
    jdbc.sql("""
            UPDATE account SET net_contributions = net_contributions + ?, units = units + ?, unit_series = ?,
                   version = version + 1
             WHERE id = ?""")
        .params(netContributionsDelta, unitsDelta, unitSeries, accountId).update();
  }
}
