package com.stocks.security;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;

/**
 * Verifies {@code stk_…} API keys (§17.2) for REST and MCP alike, in the §7.2 outcome order: unknown or malformed →
 * 401 {@code INVALID_API_KEY}; revoked → 401 {@code API_KEY_REVOKED}; expired → 401 {@code API_KEY_EXPIRED}; then the
 * owner's status: FROZEN → 403 {@code PLAYER_FROZEN}, DISABLED → 403 {@code PLAYER_DISABLED}.
 *
 * <p>Phase 0 skeleton: one indexed lookup per request ({@code api_key.key_id} joined to {@code player}).
 * TODO(WS1, Phase 1): the 60 s Caffeine cache evicted on revoke, the cached {@code PlayerQueries.status}, and the
 * asynchronous {@code last_used_at}/{@code last_used_ip} writer (at most once per minute per key).
 */
public class ApiKeyAuthenticationProvider implements AuthenticationProvider {

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final StocksProperties properties;

  public ApiKeyAuthenticationProvider(JdbcClient jdbc, TimeSource time, StocksProperties properties) {
    this.jdbc = jdbc;
    this.time = time;
    this.properties = properties;
  }

  private record KeyRow(long id, long playerId, String secretHmac, String scopes, Instant expiresAt, Instant revokedAt,
                        String playerPublicId, String handle, PlayerKind kind, PlayerRole role, PlayerStatus status) {}

  @Override
  public boolean supports(Class<?> authentication) {
    return ApiKeyAuthenticationToken.class.isAssignableFrom(authentication);
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    String token = (String) authentication.getCredentials();
    ApiKeys.Parsed parsed = ApiKeys.parse(token).orElseThrow(() -> invalid());
    if (!properties.security().hasApiKeyPepper()) {
      throw invalid(); // no pepper: no key can be verified (dev and prod always set one, §23.2)
    }
    KeyRow row = find(parsed.keyId()).orElseThrow(() -> invalid());
    if (!ApiKeys.matches(properties.security().apiKeyPepper(), parsed.secret(), row.secretHmac())) {
      throw invalid();
    }
    Instant now = time.now();
    if (row.revokedAt() != null) {
      throw new ApiKeyAuthenticationException(ErrorCode.API_KEY_REVOKED, "This API key was revoked.");
    }
    if (row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
      throw new ApiKeyAuthenticationException(ErrorCode.API_KEY_EXPIRED, "This API key has expired.");
    }
    if (row.status() == PlayerStatus.FROZEN) {
      throw new ApiKeyAuthenticationException(ErrorCode.PLAYER_FROZEN, "This player is frozen by the admin.");
    }
    if (row.status() == PlayerStatus.DISABLED) {
      throw new ApiKeyAuthenticationException(ErrorCode.PLAYER_DISABLED, "This player is disabled.");
    }
    PlayerPrincipal principal = new PlayerPrincipal(row.playerId(), row.playerPublicId(), row.handle(), row.kind(),
        row.role(), parseScopes(row.scopes()), AuthMethod.API_KEY, row.id(), null, now);
    return new PlayerAuthenticationToken(principal);
  }

  /** Space-separated scopes, restricted to the four §7.1 scopes (anything else is ignored). */
  static Set<String> parseScopes(String stored) {
    if (stored == null || stored.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(stored.trim().split("\\s+"))
        .filter(Scopes.ALL::contains)
        .collect(Collectors.toUnmodifiableSet());
  }

  private Optional<KeyRow> find(String keyId) {
    return jdbc.sql("""
            SELECT k.id, k.player_id, k.secret_hmac, k.scopes, k.expires_at, k.revoked_at,
                   p.public_id, p.handle, p.kind, p.role, p.status
              FROM api_key k JOIN player p ON p.id = k.player_id
             WHERE k.key_id = ?""")
        .param(keyId)
        .query((rs, n) -> new KeyRow(rs.getLong("id"), rs.getLong("player_id"), rs.getString("secret_hmac"),
            rs.getString("scopes"), Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "revoked_at"),
            rs.getString("public_id"), rs.getString("handle"), PlayerKind.valueOf(rs.getString("kind")),
            PlayerRole.valueOf(rs.getString("role")), PlayerStatus.valueOf(rs.getString("status"))))
        .optional();
  }

  private static ApiKeyAuthenticationException invalid() {
    return new ApiKeyAuthenticationException(ErrorCode.INVALID_API_KEY, "Unknown or malformed API key.");
  }
}
