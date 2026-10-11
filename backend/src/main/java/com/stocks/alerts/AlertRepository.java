package com.stocks.alerts;

import com.stocks.common.domain.AlertCondition;
import com.stocks.common.domain.AlertStatus;
import com.stocks.common.jdbc.Jdbc;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code price_alert} (§8.5, §19.1). The only writer of the table. Every nullable or temporal parameter is bound with
 * its SQL type ({@link Jdbc#tsParam}, typed nulls), so the same statements run on H2 and PostgreSQL ({@code -Ppg-it}).
 */
@Repository
public class AlertRepository {

  /** One {@code price_alert} row. */
  public record AlertRow(long id, String publicId, long playerId, String symbol, AlertCondition condition,
                         BigDecimal threshold, boolean repeating, AlertStatus status, boolean armed, String note,
                         Instant expiresAt, int version, int triggerCount, Instant lastTriggeredAt,
                         BigDecimal lastTriggerPrice, LocalDate lastTriggerDay, Instant createdAt, Instant updatedAt) {}

  /** A new alert's columns (status ACTIVE or PAUSED, armed). */
  public record NewAlert(String publicId, long playerId, String symbol, AlertCondition condition, BigDecimal threshold,
                         boolean repeating, String note, Instant expiresAt) {}

  static final String COLUMNS = "id, public_id, player_id, symbol, condition_type, threshold, repeating, status, armed, "
      + "note, expires_at, version, trigger_count, last_triggered_at, last_trigger_price, last_trigger_day, created_at, "
      + "updated_at";

  /** ACTIVE, PAUSED, TRIGGERED, EXPIRED; newest first within each. */
  static final String LIST_ORDER = " ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'PAUSED' THEN 1 "
      + "WHEN 'TRIGGERED' THEN 2 ELSE 3 END, created_at DESC, id DESC";

  private final JdbcClient jdbc;

  public AlertRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------- reads

  public Optional<AlertRow> byPublicId(long playerId, String publicId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM price_alert WHERE player_id = ? AND public_id = ?")
        .params(playerId, publicId).query(this::row).optional();
  }

  /** The player's alerts, optionally of one status and/or symbol, in {@link #LIST_ORDER}. */
  public List<AlertRow> list(long playerId, AlertStatus statusOrNull, String symbolOrNull) {
    StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM price_alert WHERE player_id = :player");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("player", playerId);
    if (statusOrNull != null) {
      sql.append(" AND status = :status");
      params.put("status", statusOrNull.name());
    }
    if (symbolOrNull != null) {
      sql.append(" AND symbol = :symbol");
      params.put("symbol", symbolOrNull);
    }
    sql.append(LIST_ORDER);
    return jdbc.sql(sql.toString()).params(params).query(this::row).list();
  }

  /** ACTIVE + PAUSED alerts of the player (the per-player limit). */
  public int countLive(long playerId) {
    return jdbc.sql("SELECT COUNT(*) FROM price_alert WHERE player_id = ? AND status IN ('ACTIVE','PAUSED')")
        .param(playerId).query(Integer.class).single();
  }

  /** An ACTIVE or PAUSED alert with the same symbol, condition, threshold (numerically) and repeat flag. */
  public Optional<AlertRow> findIdentical(long playerId, String symbol, AlertCondition condition, BigDecimal threshold,
                                          boolean repeating) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM price_alert WHERE player_id = ? AND symbol = ? AND condition_type = ?"
            + " AND threshold = ? AND repeating = ? AND status IN ('ACTIVE','PAUSED') ORDER BY id")
        .params(playerId, symbol, condition.name(), threshold, repeating).query(this::row).list().stream()
        .findFirst();
  }

  /** Every ACTIVE alert (the monitor's working set). */
  public List<AlertRow> active() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM price_alert WHERE status = 'ACTIVE' ORDER BY id")
        .query(this::row).list();
  }

  /** The ACTIVE alerts of one symbol. */
  public List<AlertRow> active(String symbol) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM price_alert WHERE status = 'ACTIVE' AND symbol = ? ORDER BY id")
        .param(symbol).query(this::row).list();
  }

  // ------------------------------------------------------------------------------------------- writes

  /** Inserts an ACTIVE, armed alert; returns its row. */
  public AlertRow insert(NewAlert a, Instant now) {
    jdbc.sql("""
            INSERT INTO price_alert (public_id, player_id, symbol, condition_type, threshold, repeating, status, armed,
                                     note, expires_at, version, trigger_count, last_triggered_at, last_trigger_price,
                                     last_trigger_day, created_at, updated_at)
            VALUES (:id, :player, :symbol, :cond, :threshold, :repeating, 'ACTIVE', TRUE, :note, :expires, 1, 0,
                    :nullTs, :nullPrice, :nullDay, :now, :now)""")
        .param("id", a.publicId()).param("player", a.playerId()).param("symbol", a.symbol())
        .param("cond", a.condition().name()).param("threshold", a.threshold()).param("repeating", a.repeating())
        .param("note", varchar(a.note())).param("expires", Jdbc.tsParam(a.expiresAt()))
        .param("nullTs", Jdbc.tsParam(null)).param("nullPrice", Jdbc.decimalParam(null))
        .param("nullDay", Jdbc.dateParam(null)).param("now", Jdbc.ts(now))
        .update();
    return byPublicId(a.playerId(), a.publicId()).orElseThrow();
  }

  /**
   * Replaces the editable fields and re-arms ({@code version + 1}); {@code status} is the status after the edit
   * (TRIGGERED and EXPIRED become ACTIVE, PAUSED stays PAUSED). Returns rows changed.
   */
  public int update(long id, AlertCondition condition, BigDecimal threshold, boolean repeating, Instant expiresAt,
                    String note, AlertStatus status, Instant now) {
    return jdbc.sql("""
            UPDATE price_alert
               SET condition_type = :cond, threshold = :threshold, repeating = :repeating, expires_at = :expires,
                   note = :note, status = :status, armed = TRUE, version = version + 1, updated_at = :now
             WHERE id = :id""")
        .param("cond", condition.name()).param("threshold", threshold).param("repeating", repeating)
        .param("expires", Jdbc.tsParam(expiresAt)).param("note", varchar(note)).param("status", status.name())
        .param("now", Jdbc.ts(now)).param("id", id)
        .update();
  }

  /** {@code from} → {@code to}; re-arms and bumps the version (pause, resume). Returns rows changed. */
  public int transition(long id, AlertStatus from, AlertStatus to, Instant now) {
    return jdbc.sql("""
            UPDATE price_alert SET status = :to, armed = TRUE, version = version + 1, updated_at = :now
             WHERE id = :id AND status = :from""")
        .param("to", to.name()).param("now", Jdbc.ts(now)).param("id", id).param("from", from.name())
        .update();
  }

  public int delete(long playerId, String publicId) {
    return jdbc.sql("DELETE FROM price_alert WHERE player_id = ? AND public_id = ?").params(playerId, publicId)
        .update();
  }

  /**
   * Fires an alert exactly once (§19.1): the row must still be ACTIVE, armed, unexpired and unchanged since it was
   * read ({@code version}, {@code trigger_count}). A one-shot alert becomes TRIGGERED; a repeating one stays ACTIVE,
   * disarmed. Returns 1 for the one caller that fired it, 0 otherwise.
   */
  public int fire(long id, int version, int triggerCount, boolean repeating, BigDecimal price, LocalDate tradingDay,
                  Instant now) {
    return jdbc.sql("""
            UPDATE price_alert
               SET status = :status, armed = FALSE, trigger_count = trigger_count + 1, last_triggered_at = :now,
                   last_trigger_price = :price, last_trigger_day = :day, updated_at = :now
             WHERE id = :id AND status = 'ACTIVE' AND armed = TRUE AND version = :version
               AND trigger_count = :count AND (expires_at IS NULL OR expires_at > :now)""")
        .param("status", repeating ? AlertStatus.ACTIVE.name() : AlertStatus.TRIGGERED.name())
        .param("now", Jdbc.ts(now)).param("price", Jdbc.decimalParam(price)).param("day", Jdbc.dateParam(tradingDay))
        .param("id", id).param("version", version).param("count", triggerCount)
        .update();
  }

  /** Re-arms a fired repeating alert (unchanged since it was read). */
  public int rearm(long id, int version, Instant now) {
    return jdbc.sql("""
            UPDATE price_alert SET armed = TRUE, updated_at = :now
             WHERE id = :id AND status = 'ACTIVE' AND armed = FALSE AND version = :version""")
        .param("now", Jdbc.ts(now)).param("id", id).param("version", version)
        .update();
  }

  /** ACTIVE and PAUSED alerts past their expiry become EXPIRED; returns how many. */
  public int expire(Instant now) {
    return jdbc.sql("""
            UPDATE price_alert SET status = 'EXPIRED', armed = FALSE, updated_at = :now
             WHERE status IN ('ACTIVE','PAUSED') AND expires_at IS NOT NULL AND expires_at <= :now""")
        .param("now", Jdbc.ts(now)).update();
  }

  /** Deletes TRIGGERED and EXPIRED alerts last changed before {@code cutoff}; returns how many. */
  public int purge(Instant cutoff) {
    return jdbc.sql("DELETE FROM price_alert WHERE status IN ('TRIGGERED','EXPIRED') AND updated_at < ?")
        .param(Jdbc.ts(cutoff)).update();
  }

  /** Keeps at most {@code keep} TRIGGERED/EXPIRED alerts of the player (the newest); returns how many went. */
  public int trimFinished(long playerId, int keep) {
    List<Long> ids = jdbc.sql("""
            SELECT id FROM price_alert WHERE player_id = ? AND status IN ('TRIGGERED','EXPIRED')
             ORDER BY updated_at DESC, id DESC""")
        .param(playerId).query(Long.class).list();
    if (ids.size() <= keep) {
      return 0;
    }
    return jdbc.sql("DELETE FROM price_alert WHERE id IN (:ids)").param("ids", ids.subList(keep, ids.size()))
        .update();
  }

  /** Pauses the symbol's ACTIVE alerts (a split changed its prices); returns the players affected. */
  public List<Long> pauseSymbol(String symbol, Instant now) {
    List<Long> players = jdbc.sql("""
            SELECT DISTINCT player_id FROM price_alert WHERE symbol = ? AND status = 'ACTIVE' ORDER BY player_id""")
        .param(symbol).query(Long.class).list();
    if (!players.isEmpty()) {
      jdbc.sql("""
              UPDATE price_alert SET status = 'PAUSED', armed = TRUE, version = version + 1, updated_at = :now
               WHERE symbol = :symbol AND status = 'ACTIVE'""")
          .param("now", Jdbc.ts(now)).param("symbol", symbol).update();
    }
    return players;
  }

  /** Moves the symbol's ACTIVE and PAUSED alerts to its new ticker (a name change); returns the players affected. */
  public List<Long> renameSymbol(String oldSymbol, String newSymbol, Instant now) {
    List<Long> players = jdbc.sql("""
            SELECT DISTINCT player_id FROM price_alert WHERE symbol = ? AND status IN ('ACTIVE','PAUSED')
             ORDER BY player_id""")
        .param(oldSymbol).query(Long.class).list();
    if (!players.isEmpty()) {
      jdbc.sql("""
              UPDATE price_alert SET symbol = :to, version = version + 1, updated_at = :now
               WHERE symbol = :from AND status IN ('ACTIVE','PAUSED')""")
          .param("to", newSymbol).param("now", Jdbc.ts(now)).param("from", oldSymbol).update();
    }
    return players;
  }

  // ------------------------------------------------------------------------------------------- rows

  private static SqlParameterValue varchar(String value) {
    return new SqlParameterValue(Types.VARCHAR, value);
  }

  private AlertRow row(ResultSet rs, int n) throws SQLException {
    return new AlertRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("player_id"), rs.getString("symbol"),
        AlertCondition.valueOf(rs.getString("condition_type")), rs.getBigDecimal("threshold"),
        rs.getBoolean("repeating"), AlertStatus.valueOf(rs.getString("status")), rs.getBoolean("armed"),
        rs.getString("note"), Jdbc.instant(rs, "expires_at"), rs.getInt("version"), rs.getInt("trigger_count"),
        Jdbc.instant(rs, "last_triggered_at"), rs.getBigDecimal("last_trigger_price"),
        Jdbc.date(rs, "last_trigger_day"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"));
  }
}
