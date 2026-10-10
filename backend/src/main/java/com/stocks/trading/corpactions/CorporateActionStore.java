package com.stocks.trading.corpactions;

import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Text;
import com.stocks.contract.trading.dto.CorporateActionOrigin;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * {@code corporate_action} (§8.1, §14). The table is not engine-only (§4 rule 1): the sync job and the split guard
 * insert rows from their own threads, and the engine changes a row's status inside its command transaction (the
 * {@link JdbcClient} joins it). Status changes are conditional on the expected current status, so the two never
 * overwrite each other. Every nullable parameter is typed (§8 JDBC rules).
 */
@Component
public class CorporateActionStore {

  /** Rates and cash amounts are stored as {@code NUMERIC(19,8)}. */
  static final int RATE_SCALE = 8;
  static final int DETAIL_MAX = 1000;

  private static final String SELECT = """
      SELECT c.id, c.public_id, c.provider_ref, c.symbol, c.action_type, c.ex_date, c.record_date, c.payable_date,
             c.old_rate, c.new_rate, c.cash_rate, c.new_symbol, c.status, c.origin, c.detail, c.created_at,
             c.applied_at, c.resolved_by
        FROM corporate_action c
      """;

  /** The columns of a new row. */
  public record NewAction(String publicId, String providerRef, String symbol, CorporateActionType type,
                          LocalDate exDate, LocalDate recordDate, LocalDate payableDate, BigDecimal oldRate,
                          BigDecimal newRate, BigDecimal cashRate, String newSymbol, CorporateActionStatus status,
                          CorporateActionOrigin origin, String detail, Instant createdAt) {}

  private final JdbcClient jdbc;

  public CorporateActionStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------- reads

  public Optional<CorporateActionRow> byId(long id) {
    return jdbc.sql(SELECT + " WHERE c.id = ?").param(id).query(CorporateActionStore::row).optional();
  }

  public Optional<CorporateActionRow> byPublicId(String publicId) {
    return jdbc.sql(SELECT + " WHERE c.public_id = ?").param(publicId).query(CorporateActionStore::row).optional();
  }

  public Optional<CorporateActionRow> byProviderRef(String providerRef) {
    return jdbc.sql(SELECT + " WHERE c.provider_ref = ?").param(providerRef).query(CorporateActionStore::row)
        .optional();
  }

  /** Every split row (any status) of a symbol with the given ex-date, oldest first. */
  public List<CorporateActionRow> splitsOn(String symbol, LocalDate exDate) {
    return jdbc.sql(SELECT + " WHERE c.symbol = ? AND c.ex_date = ? AND c.action_type IN ('FORWARD_SPLIT',"
            + "'REVERSE_SPLIT') ORDER BY c.id")
        .params(symbol, exDate).query(CorporateActionStore::row).list();
  }

  /** PENDING rows with {@code ex_date ≤ untilInclusive}, oldest ex-date first (the apply job's work list). */
  public List<CorporateActionRow> pendingUntil(LocalDate untilInclusive) {
    return jdbc.sql(SELECT + " WHERE c.status = 'PENDING' AND c.ex_date <= ? ORDER BY c.ex_date, c.id")
        .param(untilInclusive).query(CorporateActionStore::row).list();
  }

  /** Newest first by {@code (created_at, id)}; {@code limitPlusOne} rows at most (§6.6 lookahead paging). */
  public List<CorporateActionRow> page(CorporateActionStatus statusOrNull, Cursor cursor, int limitPlusOne) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
    List<Object> params = new ArrayList<>();
    if (statusOrNull != null) {
      sql.append(" AND c.status = ?");
      params.add(statusOrNull.name());
    }
    if (cursor != null) {
      sql.append(" AND (c.created_at < ? OR (c.created_at = ? AND c.id < ?))");
      params.add(Jdbc.ts(cursor.at()));
      params.add(Jdbc.ts(cursor.at()));
      params.add(cursor.id());
    }
    sql.append(" ORDER BY c.created_at DESC, c.id DESC LIMIT ?");
    params.add(limitPlusOne);
    return jdbc.sql(sql.toString()).params(params).query(CorporateActionStore::row).list();
  }

  /** Symbols with a NEEDS_ADMIN split whose ex-date is on or before {@code todayEt}: the corporate-action holds. */
  public List<String> symbolsOnHold(LocalDate todayEt) {
    return jdbc.sql("""
            SELECT DISTINCT symbol FROM corporate_action
             WHERE status = 'NEEDS_ADMIN' AND action_type IN ('FORWARD_SPLIT','REVERSE_SPLIT') AND ex_date <= ?
             ORDER BY symbol""")
        .param(todayEt).query(String.class).list();
  }

  /** The handle of a player (the admin who resolved an action), if any. */
  public Optional<String> handleOf(long playerId) {
    return jdbc.sql("SELECT handle FROM player WHERE id = ?").param(playerId).query(String.class).optional();
  }

  // ------------------------------------------------------------------------------------------- writes

  /** Inserts a row; returns its id. */
  public long insert(NewAction a) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.sql("""
            INSERT INTO corporate_action (public_id, provider_ref, symbol, action_type, ex_date, record_date,
                                          payable_date, old_rate, new_rate, cash_rate, new_symbol, status, origin,
                                          detail, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(a.publicId(), text(a.providerRef()), a.symbol(), a.type().name(), a.exDate(),
            Jdbc.dateParam(a.recordDate()), Jdbc.dateParam(a.payableDate()), rate(a.oldRate()), rate(a.newRate()),
            rate(a.cashRate()), text(a.newSymbol()), a.status().name(), a.origin().name(), text(detail(a.detail())),
            Jdbc.ts(a.createdAt()))
        .update(keys, "id");
    return keys.getKeyAs(Long.class);
  }

  /** Refreshes a still-PENDING provider row with the provider's latest data; false if it is no longer PENDING. */
  public boolean refreshPending(long id, String symbol, LocalDate exDate, LocalDate recordDate, LocalDate payableDate,
                                BigDecimal oldRate, BigDecimal newRate, BigDecimal cashRate, String newSymbol) {
    return jdbc.sql("""
            UPDATE corporate_action SET symbol = ?, ex_date = ?, record_date = ?, payable_date = ?, old_rate = ?,
                   new_rate = ?, cash_rate = ?, new_symbol = ?
             WHERE id = ? AND status = 'PENDING'""")
        .params(symbol, exDate, Jdbc.dateParam(recordDate), Jdbc.dateParam(payableDate), rate(oldRate),
            rate(newRate), rate(cashRate), text(newSymbol), id)
        .update() == 1;
  }

  /**
   * Moves a row from {@code expected} to {@code status}; false (and nothing changes) if the row is no longer in
   * {@code expected}. {@code appliedAtOrNull} and {@code resolvedByOrNull} are written only when non-null.
   */
  public boolean transition(long id, CorporateActionStatus expected, CorporateActionStatus status, String detail,
                            Instant appliedAtOrNull, Long resolvedByOrNull) {
    return jdbc.sql("""
            UPDATE corporate_action SET status = ?, detail = ?, applied_at = COALESCE(?, applied_at),
                   resolved_by = COALESCE(?, resolved_by)
             WHERE id = ? AND status = ?""")
        .params(status.name(), text(detail(detail)), Jdbc.tsParam(appliedAtOrNull),
            new SqlParameterValue(Types.BIGINT, resolvedByOrNull), id, expected.name())
        .update() == 1;
  }

  /** The admin's confirmed split rates (and the type they imply) before a resolve-APPLY. */
  public void setSplitRates(long id, CorporateActionType type, BigDecimal oldRate, BigDecimal newRate) {
    jdbc.sql("UPDATE corporate_action SET action_type = ?, old_rate = ?, new_rate = ? WHERE id = ?")
        .params(type.name(), rate(oldRate), rate(newRate), id).update();
  }

  // ------------------------------------------------------------------------------------------- helpers

  /** A rate normalized for {@code NUMERIC(19,8)} (null-safe, typed). */
  static SqlParameterValue rate(BigDecimal v) {
    return new SqlParameterValue(Types.NUMERIC, normalizeRate(v));
  }

  static BigDecimal normalizeRate(BigDecimal v) {
    if (v == null) {
      return null;
    }
    BigDecimal s = v.stripTrailingZeros();
    return s.scale() > RATE_SCALE ? s.setScale(RATE_SCALE, RoundingMode.HALF_EVEN) : s;
  }

  static String detail(String detail) {
    return detail == null ? null : Text.clip(detail, DETAIL_MAX);
  }

  /** {@code previous | addition}, clipped to the column. */
  static String appendDetail(String previous, String addition) {
    if (previous == null || previous.isBlank()) {
      return detail(addition);
    }
    return detail(previous + " | " + addition);
  }

  private static SqlParameterValue text(String s) {
    return new SqlParameterValue(Types.VARCHAR, s);
  }

  static CorporateActionRow row(ResultSet rs, int n) throws SQLException {
    return new CorporateActionRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("provider_ref"),
        rs.getString("symbol"), CorporateActionType.valueOf(rs.getString("action_type")), Jdbc.date(rs, "ex_date"),
        Jdbc.date(rs, "record_date"), Jdbc.date(rs, "payable_date"), rs.getBigDecimal("old_rate"),
        rs.getBigDecimal("new_rate"), rs.getBigDecimal("cash_rate"), rs.getString("new_symbol"),
        CorporateActionStatus.valueOf(rs.getString("status")), CorporateActionOrigin.valueOf(rs.getString("origin")),
        rs.getString("detail"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "applied_at"),
        Jdbc.longOrNull(rs, "resolved_by"));
  }
}
