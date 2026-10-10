package com.stocks.audit;

import com.stocks.common.domain.PlayerKind;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One {@code audit_log} row before it is written (§17.7). Build with {@link #of} and the {@code with…} methods:
 *
 * <pre>{@code
 * audit.record(AuditEntry.of(AuditCategory.AUTH, "LOGIN_FAIL", AuditOutcome.DENIED)
 *     .withMeta(meta).withTarget("player", handle).withDetail("code", "BAD_CREDENTIALS"));
 * }</pre>
 *
 * {@code detail} becomes a JSON object; values under keys that look secret ({@code password}, {@code secret},
 * {@code token}, {@code pepper}) are replaced by {@code "***"}.
 */
public record AuditEntry(AuditCategory category, String action, AuditOutcome outcome, Long actorPlayerId,
                         PlayerKind actorKind, Long apiKeyId, String oauthClientId, RequestMeta meta,
                         String targetType, String targetId, Map<String, Object> detail) {

  public AuditEntry {
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(outcome, "outcome");
    detail = detail == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
  }

  public static AuditEntry of(AuditCategory category, String action, AuditOutcome outcome) {
    return new AuditEntry(category, action, outcome, null, null, null, null, null, null, null, Map.of());
  }

  /** Actor, kind, API key and OAuth client from the authenticated principal (null = anonymous). */
  public AuditEntry withActor(PlayerPrincipal p) {
    if (p == null) {
      return this;
    }
    return new AuditEntry(category, action, outcome, p.playerId(), p.kind(), p.apiKeyId(), p.oauthClientId(), meta,
        targetType, targetId, detail);
  }

  public AuditEntry withActor(long playerId, PlayerKind kind) {
    return new AuditEntry(category, action, outcome, playerId, kind, apiKeyId, oauthClientId, meta, targetType,
        targetId, detail);
  }

  public AuditEntry withMeta(RequestMeta requestMeta) {
    return new AuditEntry(category, action, outcome, actorPlayerId, actorKind, apiKeyId, oauthClientId, requestMeta,
        targetType, targetId, detail);
  }

  public AuditEntry withTarget(String type, String id) {
    return new AuditEntry(category, action, outcome, actorPlayerId, actorKind, apiKeyId, oauthClientId, meta, type,
        id, detail);
  }

  public AuditEntry withDetail(String key, Object value) {
    Map<String, Object> copy = new LinkedHashMap<>(detail);
    copy.put(key, value);
    return new AuditEntry(category, action, outcome, actorPlayerId, actorKind, apiKeyId, oauthClientId, meta,
        targetType, targetId, copy);
  }
}
