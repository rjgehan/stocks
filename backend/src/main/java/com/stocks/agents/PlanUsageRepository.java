package com.stocks.agents;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.agents.dto.PlanUsageKind;
import com.stocks.contract.agents.dto.PlanUsageSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code plan_usage_snapshot} (V15). Written only by {@link PlanUsageService}. */
@Repository
public class PlanUsageRepository {

  /** One snapshot with the handle of the bot whose key sent it. */
  public record Row(long playerId, String handle, PlanUsageKind kind, PlanUsageSource source, Instant takenAt,
                    LocalDate tradeDate, String roundRef, String runRef, BigDecimal sessionPercent,
                    Instant sessionResetsAt, BigDecimal weeklyPercent, Instant weeklyResetsAt, Long tokensInput,
                    Long tokensCacheWrite, Long tokensCacheRead, Long tokensOutput, BigDecimal costUsd,
                    Instant receivedAt) {}

  private final JdbcClient jdbc;

  public PlanUsageRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Row r) {
    jdbc.sql("""
            INSERT INTO plan_usage_snapshot (player_id, kind, source, taken_at, trade_date, round_ref, run_ref,
                                             session_percent, session_resets_at, weekly_percent, weekly_resets_at,
                                             tokens_input, tokens_cache_write, tokens_cache_read, tokens_output,
                                             cost_usd, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(r.playerId(), r.kind().name(), r.source().name(), Jdbc.tsParam(r.takenAt()),
            Jdbc.dateParam(r.tradeDate()), r.roundRef(), r.runRef(), Jdbc.decimalParam(r.sessionPercent()),
            Jdbc.tsParam(r.sessionResetsAt()), Jdbc.decimalParam(r.weeklyPercent()), Jdbc.tsParam(r.weeklyResetsAt()),
            nullableLong(r.tokensInput()), nullableLong(r.tokensCacheWrite()), nullableLong(r.tokensCacheRead()),
            nullableLong(r.tokensOutput()), Jdbc.decimalParam(r.costUsd()), Jdbc.tsParam(r.receivedAt()))
        .update();
  }

  /** How many snapshots the bot's key sent after {@code since} (the report budget). */
  public int receivedSince(long playerId, Instant since) {
    return jdbc.sql("SELECT COUNT(*) FROM plan_usage_snapshot WHERE player_id = ? AND received_at > ?")
        .params(playerId, Jdbc.tsParam(since)).query(Integer.class).single();
  }

  /** Snapshots sent with the keys of {@code players} and taken after {@code since}, newest first. */
  public List<Row> since(List<Long> players, Instant since, int limit) {
    if (players.isEmpty()) {
      return List.of();
    }
    String in = String.join(",", players.stream().map(p -> "?").toList());
    Object[] params = new Object[players.size() + 2];
    for (int i = 0; i < players.size(); i++) {
      params[i] = players.get(i);
    }
    params[players.size()] = Jdbc.tsParam(since);
    params[players.size() + 1] = limit;
    return jdbc.sql("""
            SELECT s.*, p.handle FROM plan_usage_snapshot s JOIN player p ON p.id = s.player_id
             WHERE s.player_id IN (%s) AND s.taken_at > ?
             ORDER BY s.taken_at DESC, s.id DESC
             FETCH FIRST ? ROWS ONLY""".formatted(in))
        .params(params).query(PlanUsageRepository::row).list();
  }

  private static Object nullableLong(Long v) {
    return v == null ? Jdbc.nullOf(Types.BIGINT) : v;
  }

  private static Long longOrNull(ResultSet rs, String column) throws SQLException {
    long v = rs.getLong(column);
    return rs.wasNull() ? null : v;
  }

  private static Row row(ResultSet rs, int n) throws SQLException {
    return new Row(rs.getLong("player_id"), rs.getString("handle"), PlanUsageKind.valueOf(rs.getString("kind")),
        PlanUsageSource.valueOf(rs.getString("source")), Jdbc.instant(rs, "taken_at"), Jdbc.date(rs, "trade_date"),
        rs.getString("round_ref"), rs.getString("run_ref"), rs.getBigDecimal("session_percent"),
        Jdbc.instant(rs, "session_resets_at"), rs.getBigDecimal("weekly_percent"),
        Jdbc.instant(rs, "weekly_resets_at"), longOrNull(rs, "tokens_input"), longOrNull(rs, "tokens_cache_write"),
        longOrNull(rs, "tokens_cache_read"), longOrNull(rs, "tokens_output"), rs.getBigDecimal("cost_usd"),
        Jdbc.instant(rs, "received_at"));
  }
}
