package com.stocks.apilog;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.web.Cursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code api_access_log} (§17.10). Written only by {@link ApiAccessLog}'s single flusher, one UPDATE-or-INSERT per
 * hourly bucket (one writer, so there is no MERGE race; {@code ux_api_access_bucket} is the backstop).
 */
@Repository
public class ApiAccessLogRepository {

  /** The counts of one bucket to add to its row. */
  public record Delta(Instant hourStart, long playerId, Long apiKeyId, String keyRef, Channel channel, String endpoint,
                      String outcome, int calls, long totalMs, int maxMs, Instant firstAt, Instant lastAt,
                      String lastIp, String lastClient) {}

  /** One row joined with its player and key. */
  public record Row(long id, Instant hourStart, long playerId, String handle, String displayName, PlayerKind kind,
                    String keyRef, String keyName, Channel channel, String endpoint, String outcome, int calls,
                    long totalMs, int maxMs, Instant firstAt, Instant lastAt, String lastIp, String lastClient) {}

  private final JdbcClient jdbc;

  public ApiAccessLogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Adds {@code d} to its bucket's row, creating the row if needed. */
  public void add(Delta d) {
    int updated = jdbc.sql("""
            UPDATE api_access_log
               SET calls = calls + ?, total_ms = total_ms + ?, max_ms = GREATEST(max_ms, ?),
                   first_at = LEAST(first_at, ?), last_at = GREATEST(last_at, ?), last_ip = ?, last_client = ?
             WHERE bucket_start = ? AND key_ref = ? AND channel = ? AND endpoint = ? AND outcome = ?""")
        .params(d.calls(), d.totalMs(), d.maxMs(), Jdbc.tsParam(d.firstAt()), Jdbc.tsParam(d.lastAt()),
            new SqlParameterValue(Types.VARCHAR, d.lastIp()), new SqlParameterValue(Types.VARCHAR, d.lastClient()),
            Jdbc.tsParam(d.hourStart()), d.keyRef(), d.channel().name(), d.endpoint(), d.outcome())
        .update();
    if (updated > 0) {
      return;
    }
    jdbc.sql("""
            INSERT INTO api_access_log (bucket_start, player_id, api_key_id, key_ref, channel, endpoint, outcome, calls,
                                        total_ms, max_ms, first_at, last_at, last_ip, last_client)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(Jdbc.tsParam(d.hourStart()), d.playerId(), new SqlParameterValue(Types.BIGINT, d.apiKeyId()),
            d.keyRef(), d.channel().name(), d.endpoint(), d.outcome(), d.calls(), d.totalMs(), d.maxMs(),
            Jdbc.tsParam(d.firstAt()), Jdbc.tsParam(d.lastAt()), new SqlParameterValue(Types.VARCHAR, d.lastIp()),
            new SqlParameterValue(Types.VARCHAR, d.lastClient()))
        .update();
  }

  /** {@code api_key.key_id} of a key row. */
  public Optional<String> keyRef(long apiKeyId) {
    return jdbc.sql("SELECT key_id FROM api_key WHERE id = ?").param(apiKeyId).query(String.class).optional();
  }

  /**
   * Newest {@code last_at} first; {@code playerIds} null = every player, empty = nothing. {@code limit} rows at most.
   */
  public List<Row> page(Collection<Long> playerIds, Cursor cursor, int limit) {
    if (playerIds != null && playerIds.isEmpty()) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder("""
        SELECT l.id, l.bucket_start, l.player_id, p.handle, p.display_name, p.kind, l.key_ref, k.name AS key_name,
               l.channel, l.endpoint, l.outcome, l.calls, l.total_ms, l.max_ms, l.first_at, l.last_at, l.last_ip,
               l.last_client
          FROM api_access_log l JOIN player p ON p.id = l.player_id LEFT JOIN api_key k ON k.id = l.api_key_id
         WHERE 1 = 1""");
    List<Object> params = new ArrayList<>();
    if (playerIds != null) {
      sql.append(" AND l.player_id IN (")
          .append(playerIds.stream().map(id -> "?").collect(Collectors.joining(", "))).append(")");
      params.addAll(playerIds);
    }
    if (cursor != null) {
      sql.append(" AND (l.last_at < ? OR (l.last_at = ? AND l.id < ?))");
      params.add(Jdbc.ts(cursor.at()));
      params.add(Jdbc.ts(cursor.at()));
      params.add(cursor.id());
    }
    sql.append(" ORDER BY l.last_at DESC, l.id DESC LIMIT ").append(Math.max(1, limit));
    return jdbc.sql(sql.toString()).params(params).query(ApiAccessLogRepository::row).list();
  }

  /** Deletes buckets whose hour started before {@code before}; returns the rows deleted. */
  public int deleteOlderThan(Instant before) {
    return jdbc.sql("DELETE FROM api_access_log WHERE bucket_start < ?").param(Jdbc.ts(before)).update();
  }

  private static Row row(ResultSet rs, int n) throws SQLException {
    return new Row(rs.getLong("id"), Jdbc.instant(rs, "bucket_start"), rs.getLong("player_id"), rs.getString("handle"),
        rs.getString("display_name"), PlayerKind.valueOf(rs.getString("kind")), rs.getString("key_ref"),
        rs.getString("key_name"), Channel.valueOf(rs.getString("channel")), rs.getString("endpoint"),
        rs.getString("outcome"), rs.getInt("calls"), rs.getLong("total_ms"), rs.getInt("max_ms"),
        Jdbc.instant(rs, "first_at"), Jdbc.instant(rs, "last_at"), rs.getString("last_ip"),
        rs.getString("last_client"));
  }
}
