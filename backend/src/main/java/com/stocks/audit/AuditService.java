package com.stocks.audit;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Text;
import com.stocks.contract.security.RequestMeta;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the security record, {@code audit_log} (§17.7, append-only; PG triggers forbid UPDATE/DELETE). Every row
 * carries actor, kind, channel, API key, MCP client and tool, IP, user agent and request id. Client-controlled strings
 * arrive bounded by {@link RequestMeta}; every column is clipped again here, so an insert never fails on length.
 * Secrets are never logged: detail values under secret-looking keys are redacted, and callers never pass raw tokens.
 *
 * <p>The row is written through the caller's transaction when there is one (JdbcClient is transaction-aware).
 * Reads are not audited in Phase 1. TODO(WS1, Phase 1): {@code AuditQueries}
 * for {@code GET /admin/audit}.
 */
@Service
public class AuditService {

  static final int MAX_DETAIL = 4000;

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final JsonMapper jsonMapper;

  public AuditService(JdbcClient jdbc, TimeSource time, JsonMapper jsonMapper) {
    this.jdbc = jdbc;
    this.time = time;
    this.jsonMapper = jsonMapper;
  }

  public void record(AuditEntry e) {
    RequestMeta m = e.meta();
    jdbc.sql("""
            INSERT INTO audit_log (occurred_at, category, action, outcome, actor_player_id, actor_kind, channel,
                                   api_key_id, oauth_client_id, mcp_client, mcp_tool, ip, user_agent, request_id,
                                   target_type, target_id, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(
            Jdbc.ts(time.now()),
            e.category().name(),
            Text.clip(e.action(), 48),
            e.outcome().name(),
            e.actorPlayerId(),
            e.actorKind() == null ? null : e.actorKind().name(),
            m == null || m.channel() == null ? null : m.channel().name(),
            e.apiKeyId(),
            Text.clip(e.oauthClientId(), 200),
            m == null ? null : Text.clip(m.mcpClient(), 120),
            m == null ? null : Text.clip(m.mcpTool(), 64),
            m == null ? null : Text.clip(m.ip(), 45),
            m == null ? null : Text.clip(m.userAgent(), 300),
            m == null ? null : Text.clip(m.requestId(), 64),
            Text.clip(e.targetType(), 24),
            Text.clip(e.targetId(), 64),
            detailJson(e.detail()))
        .update();
  }

  String detailJson(Map<String, Object> detail) {
    if (detail == null || detail.isEmpty()) {
      return null;
    }
    Map<String, Object> safe = new LinkedHashMap<>();
    detail.forEach((k, v) -> safe.put(k, isSecretKey(k) ? "***" : v));
    String json = jsonMapper.writeValueAsString(safe);
    if (json.length() <= MAX_DETAIL) {
      return json;
    }
    return jsonMapper.writeValueAsString(Map.of("truncated", true, "length", json.length()));
  }

  static boolean isSecretKey(String key) {
    String k = key.toLowerCase(Locale.ROOT);
    return k.contains("password") || k.contains("secret") || k.contains("token") || k.contains("pepper");
  }
}
