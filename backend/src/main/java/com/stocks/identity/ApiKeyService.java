package com.stocks.identity;

import com.stocks.common.error.ApiException;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.security.Scopes;
import com.stocks.security.ApiKeys;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Issues {@code stk_<keyId>_<secret>} API keys (§17.2). Only {@code key_id} and the HMAC of the secret are stored;
 * the token is returned once and never logged. Verification is {@code security.ApiKeyAuthenticationProvider}.
 *
 * <p>Phase 0: issuance for the dev seed and tests. TODO(WS1, Phase 1): personal keys ({@code /me/api-keys}, read-only
 * by default, {@code trade} only with {@code api_trading_allowed}, {@code @RecentAuth}), admin keys, list, revoke with
 * cache eviction, revoke-all on password change.
 */
@Service
public class ApiKeyService {

  /** A freshly issued key; {@link #token()} is the only copy of the secret. */
  public record IssuedKey(long id, String keyId, String token, Set<String> scopes, Instant createdAt,
                          Instant expiresAt) {
    @Override
    public String toString() {
      return "IssuedKey[keyId=" + keyId + ", scopes=" + scopes + "]";
    }
  }

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final StocksProperties properties;
  private final SecureRandom random = new SecureRandom();

  public ApiKeyService(JdbcClient jdbc, TimeSource time, StocksProperties properties) {
    this.jdbc = jdbc;
    this.time = time;
    this.properties = properties;
  }

  /**
   * Creates a key for {@code playerId}.
   *
   * @param scopes          non-empty subset of {@link Scopes#ALL}
   * @param expiresAtOrNull null = no expiry
   * @param createdBy       the player who issued it (the owner for personal keys, the admin otherwise)
   */
  public IssuedKey issue(long playerId, String name, Set<String> scopes, Instant expiresAtOrNull, long createdBy) {
    if (name == null || name.isBlank() || name.length() > 64) {
      throw ApiException.validation("name", "1-64 characters");
    }
    if (scopes == null || scopes.isEmpty() || !Scopes.ALL.containsAll(scopes)) {
      throw ApiException.validation("scopes", "A non-empty subset of " + new TreeSet<>(Scopes.ALL));
    }
    if (!properties.security().hasApiKeyPepper()) {
      throw new IllegalStateException("stocks.security.api-key-pepper (API_KEY_PEPPER) is not configured");
    }
    String stored = String.join(" ", new TreeSet<>(scopes));
    Instant now = time.now();
    for (int attempt = 0; attempt < 5; attempt++) {
      String keyId = ApiKeys.newKeyId(random);
      String secret = ApiKeys.newSecret(random);
      try {
        jdbc.sql("""
                INSERT INTO api_key (key_id, player_id, name, secret_hmac, scopes, created_by, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(keyId, playerId, name.trim(), ApiKeys.hmacHex(properties.security().apiKeyPepper(), secret),
                stored, createdBy, Jdbc.ts(now), Jdbc.ts(expiresAtOrNull))
            .update();
      } catch (DuplicateKeyException collision) {
        continue; // 60-bit key ids: practically never
      }
      long id = jdbc.sql("SELECT id FROM api_key WHERE key_id = ?").param(keyId).query(Long.class).single();
      return new IssuedKey(id, keyId, ApiKeys.format(keyId, secret), Set.copyOf(scopes), now, expiresAtOrNull);
    }
    throw new IllegalStateException("Could not allocate a unique API key id");
  }

  /** True if the player has a key that is neither revoked nor expired. */
  public boolean hasActiveKey(long playerId) {
    return jdbc.sql("""
            SELECT COUNT(*) FROM api_key
             WHERE player_id = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)""")
        .params(playerId, Jdbc.ts(time.now()))
        .query(Long.class).single() > 0;
  }
}
