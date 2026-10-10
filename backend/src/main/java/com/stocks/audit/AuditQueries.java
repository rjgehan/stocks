package com.stocks.audit;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.admin.dto.AuditRowDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code audit_log} for {@code GET /admin/audit} and the admin player detail (§21.1). Newest first by
 * {@code (occurred_at, id)} with an opaque keyset cursor (§6.6). Filters are added to the SQL only when present, so no
 * statement ever binds an untyped null (§8 JDBC rules).
 */
@Service
public class AuditQueries {

  private static final Logger log = LoggerFactory.getLogger(AuditQueries.class);

  /** {@code GET /admin/audit} filters; every member optional. {@code actorPlayerId} resolves the {@code actor} handle. */
  public record Filter(AuditCategory category, String action, Long actorPlayerId, Instant from, Instant to) {
    public static Filter none() {
      return new Filter(null, null, null, null, null);
    }
  }

  private static final String SELECT = """
      SELECT a.id, a.occurred_at, a.category, a.action, a.outcome, a.actor_kind, a.channel, a.mcp_client,
             a.mcp_tool, a.ip, a.request_id, a.target_type, a.target_id, a.detail,
             p.handle AS actor_handle, k.key_id AS api_key_public
        FROM audit_log a
        LEFT JOIN player p ON p.id = a.actor_player_id
        LEFT JOIN api_key k ON k.id = a.api_key_id""";

  private final JdbcClient jdbc;
  private final JsonMapper jsonMapper;

  public AuditQueries(JdbcClient jdbc, JsonMapper jsonMapper) {
    this.jdbc = jdbc;
    this.jsonMapper = jsonMapper;
  }

  public Page<AuditRowDto> list(Filter filter, Cursor cursor, int limit) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
    List<Object> params = new ArrayList<>();
    if (filter.category() != null) {
      sql.append(" AND a.category = ?");
      params.add(filter.category().name());
    }
    if (filter.action() != null && !filter.action().isBlank()) {
      sql.append(" AND a.action = ?");
      params.add(filter.action().trim().toUpperCase(Locale.ROOT));
    }
    if (filter.actorPlayerId() != null) {
      sql.append(" AND a.actor_player_id = ?");
      params.add(filter.actorPlayerId());
    }
    if (filter.from() != null) {
      sql.append(" AND a.occurred_at >= ?");
      params.add(Jdbc.ts(filter.from()));
    }
    if (filter.to() != null) {
      sql.append(" AND a.occurred_at < ?");
      params.add(Jdbc.ts(filter.to()));
    }
    if (cursor != null) {
      sql.append(" AND (a.occurred_at < ? OR (a.occurred_at = ? AND a.id < ?))");
      params.add(Jdbc.ts(cursor.at()));
      params.add(Jdbc.ts(cursor.at()));
      params.add(cursor.id());
    }
    sql.append(" ORDER BY a.occurred_at DESC, a.id DESC LIMIT ").append(limit + 1);
    List<AuditRowDto> rows = jdbc.sql(sql.toString()).params(params).query(this::row).list();
    return Page.fromLookahead(rows, limit, r -> Cursor.of(r.occurredAt(), r.id()));
  }

  /** The latest rows where the player is the actor or the target (admin player detail). */
  public List<AuditRowDto> recentFor(long playerId, String playerPublicId, String handle, int limit) {
    return jdbc.sql(SELECT
            + " WHERE a.actor_player_id = ? OR (a.target_type = 'player' AND a.target_id = ?)"
            + " OR (a.target_type = 'handle' AND a.target_id = ?)"
            + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT " + Math.max(1, limit))
        .params(playerId, playerPublicId, handle)
        .query(this::row).list();
  }

  /**
   * Parses an {@code ?category=} value.
   *
   * @throws ApiException 400 {@code VALIDATION_FAILED}
   */
  public static AuditCategory category(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return AuditCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("category", "One of " + List.of(AuditCategory.values()));
    }
  }

  private AuditRowDto row(ResultSet rs, int n) throws SQLException {
    String kind = rs.getString("actor_kind");
    String channel = rs.getString("channel");
    return new AuditRowDto(rs.getLong("id"), Jdbc.instant(rs, "occurred_at"), rs.getString("category"),
        rs.getString("action"), rs.getString("outcome"), rs.getString("actor_handle"),
        kind == null ? null : PlayerKind.valueOf(kind), channel == null ? null : Channel.valueOf(channel),
        rs.getString("api_key_public"), rs.getString("mcp_client"), rs.getString("mcp_tool"), rs.getString("ip"),
        rs.getString("request_id"), rs.getString("target_type"), rs.getString("target_id"),
        detail(rs.getString("detail")));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> detail(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return jsonMapper.readValue(json, Map.class);
    } catch (JacksonException e) {
      log.debug("Unreadable audit detail: {}", e.getOriginalMessage());
      return Map.of("raw", json);
    }
  }
}
