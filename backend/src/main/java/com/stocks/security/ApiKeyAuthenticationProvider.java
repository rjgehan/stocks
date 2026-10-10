package com.stocks.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import java.time.Duration;
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
 * <p>Key rows ({@code api_key ⨝ player}: HMAC, scopes, expiry, revocation, owner) are cached for
 * {@code stocks.security.api-key-cache-ttl} (60 s); {@code identity.ApiKeyService} evicts a key the moment it is
 * revoked or its scopes change ({@link #evict}, {@link #evictPlayer}). The owner's status comes from
 * {@link PlayerQueries#status}, whose own cache {@code AdminPlayerService} evicts synchronously, so a freeze takes
 * effect on the very next request. {@code last_used_*} is written by {@link ActivityRecorder}.
 */
public class ApiKeyAuthenticationProvider implements AuthenticationProvider {

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final StocksProperties properties;
  private final PlayerQueries players;
  private final Cache<String, KeyRow> cache;

  public ApiKeyAuthenticationProvider(JdbcClient jdbc, TimeSource time, StocksProperties properties,
                                      PlayerQueries players) {
    this.jdbc = jdbc;
    this.time = time;
    this.properties = properties;
    this.players = players;
    Duration ttl = properties.security().apiKeyCacheTtl();
    this.cache = ttl == null || ttl.isZero() || ttl.isNegative() ? null
        : Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
  }

  record KeyRow(long id, long playerId, String secretHmac, String scopes, Instant expiresAt, Instant revokedAt,
                String playerPublicId, String handle, PlayerKind kind, PlayerRole role) {}

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
    KeyRow row = lookup(parsed.keyId()).orElseThrow(() -> invalid());
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
    PlayerStatus status = players.status(row.playerId());
    if (status == PlayerStatus.FROZEN) {
      throw new ApiKeyAuthenticationException(ErrorCode.PLAYER_FROZEN, "This player is frozen by the admin.");
    }
    if (status == PlayerStatus.DISABLED) {
      throw new ApiKeyAuthenticationException(ErrorCode.PLAYER_DISABLED, "This player is disabled.");
    }
    PlayerPrincipal principal = new PlayerPrincipal(row.playerId(), row.playerPublicId(), row.handle(), row.kind(),
        row.role(), parseScopes(row.scopes()), AuthMethod.API_KEY, row.id(), null, now);
    return new PlayerAuthenticationToken(principal);
  }

  /** Drops one key from the cache (revocation, scope change). */
  public void evict(String keyId) {
    if (cache != null && keyId != null) {
      cache.invalidate(keyId);
    }
  }

  /** Drops every cached key of a player (role change, revoke-all). */
  public void evictPlayer(long playerId) {
    if (cache != null) {
      cache.asMap().values().removeIf(r -> r.playerId() == playerId);
    }
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

  private Optional<KeyRow> lookup(String keyId) {
    if (cache == null) {
      return find(keyId);
    }
    KeyRow cached = cache.getIfPresent(keyId);
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<KeyRow> row = find(keyId);
    row.ifPresent(r -> cache.put(keyId, r)); // unknown ids are not cached (cannot be filled by guessing)
    return row;
  }

  private Optional<KeyRow> find(String keyId) {
    return jdbc.sql("""
            SELECT k.id, k.player_id, k.secret_hmac, k.scopes, k.expires_at, k.revoked_at,
                   p.public_id, p.handle, p.kind, p.role
              FROM api_key k JOIN player p ON p.id = k.player_id
             WHERE k.key_id = ?""")
        .param(keyId)
        .query((rs, n) -> new KeyRow(rs.getLong("id"), rs.getLong("player_id"), rs.getString("secret_hmac"),
            rs.getString("scopes"), Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "revoked_at"),
            rs.getString("public_id"), rs.getString("handle"), PlayerKind.valueOf(rs.getString("kind")),
            PlayerRole.valueOf(rs.getString("role"))))
        .optional();
  }

  private static ApiKeyAuthenticationException invalid() {
    return new ApiKeyAuthenticationException(ErrorCode.INVALID_API_KEY, "Unknown or malformed API key.");
  }
}
