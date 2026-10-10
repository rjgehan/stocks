package com.stocks.identity;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.identity.dto.ApiKeyCreatedDto;
import com.stocks.contract.identity.dto.ApiKeyInfoDto;
import com.stocks.contract.identity.dto.ApiKeySnippetsDto;
import com.stocks.contract.security.Scopes;
import com.stocks.security.ApiKeyAuthenticationProvider;
import com.stocks.security.ApiKeys;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * API keys {@code stk_<keyId>_<secret>} (§17.2). Only {@code key_id} and the peppered HMAC of the secret are stored;
 * the token is returned once ({@link ApiKeyCreatedDto}) and never logged. Verification is
 * {@code security.ApiKeyAuthenticationProvider}; every revocation or scope change here evicts its cache at once.
 *
 * <ul>
 *   <li><b>Personal keys</b> (humans, {@code /me/api-keys}): read-only by default; {@code trade} only when the admin
 *       set {@code player.api_trading_allowed}, else 403 {@code FORBIDDEN_SCOPE}; all revoked on a password change.</li>
 *   <li><b>Bot keys</b> (admin, {@code /admin/players/{handle}/api-keys}): all four scopes by default.</li>
 *   <li>There is no admin scope (§17.4).</li>
 * </ul>
 */
@Service
public class ApiKeyService {

  /** Upper bound on a player's live keys (a bot needs one or two; a human a few scripts). */
  public static final int MAX_ACTIVE_KEYS = 20;
  public static final int MAX_EXPIRES_IN_DAYS = 365;
  static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{N} _.:()\\-]{1,64}$");

  /** A freshly issued key; {@link #token()} is the only copy of the secret. */
  public record IssuedKey(long id, String keyId, String token, Set<String> scopes, Instant createdAt,
                          Instant expiresAt) {
    @Override
    public String toString() {
      return "IssuedKey[keyId=" + keyId + ", scopes=" + scopes + "]";
    }
  }

  private static final String INFO_COLUMNS =
      "key_id, name, scopes, created_at, expires_at, last_used_at, last_used_ip, revoked_at";

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final StocksProperties properties;
  private final ApiKeyAuthenticationProvider keyCache;
  private final SecureRandom random = new SecureRandom();

  public ApiKeyService(JdbcClient jdbc, TimeSource time, StocksProperties properties,
                       ApiKeyAuthenticationProvider keyCache) {
    this.jdbc = jdbc;
    this.time = time;
    this.properties = properties;
    this.keyCache = keyCache;
  }

  // ------------------------------------------------------------------------------------------- issue

  /**
   * Creates a key for {@code playerId}.
   *
   * @param scopes          non-empty subset of {@link Scopes#ALL}
   * @param expiresAtOrNull null = no expiry
   * @param createdBy       the player who issued it (the owner for personal keys, the admin otherwise)
   */
  public IssuedKey issue(long playerId, String name, Set<String> scopes, Instant expiresAtOrNull, long createdBy) {
    String n = validateName(name);
    if (scopes == null || scopes.isEmpty() || !Scopes.ALL.containsAll(scopes)) {
      throw ApiException.validation("scopes", "A non-empty subset of " + new TreeSet<>(Scopes.ALL));
    }
    if (!properties.security().hasApiKeyPepper()) {
      throw new IllegalStateException("stocks.security.api-key-pepper (API_KEY_PEPPER) is not configured");
    }
    if (countActive(playerId) >= MAX_ACTIVE_KEYS) {
      throw ApiException.validation("name", "At most " + MAX_ACTIVE_KEYS + " active keys per player; revoke one first");
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
            .params(keyId, playerId, n, ApiKeys.hmacHex(properties.security().apiKeyPepper(), secret),
                stored, createdBy, Jdbc.ts(now), Jdbc.tsParam(expiresAtOrNull))
            .update();
      } catch (DuplicateKeyException collision) {
        continue; // 60-bit key ids: practically never
      }
      long id = jdbc.sql("SELECT id FROM api_key WHERE key_id = ?").param(keyId).query(Long.class).single();
      return new IssuedKey(id, keyId, ApiKeys.format(keyId, secret), Set.copyOf(scopes), now, expiresAtOrNull);
    }
    throw new IllegalStateException("Could not allocate a unique API key id");
  }

  /**
   * Parses a requested scope list (null or empty → {@code defaults}).
   *
   * @throws ApiException 400 {@code VALIDATION_FAILED} for an unknown scope
   */
  public static Set<String> parseScopes(Collection<String> requested, Set<String> defaults) {
    if (requested == null || requested.isEmpty()) {
      return defaults;
    }
    Set<String> out = new LinkedHashSet<>();
    for (String s : requested) {
      String scope = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
      if (!Scopes.ALL.contains(scope)) {
        throw ApiException.validation("scopes", "Unknown scope '" + s + "'; allowed: " + new TreeSet<>(Scopes.ALL));
      }
      out.add(scope);
    }
    return Set.copyOf(out);
  }

  /**
   * {@code expiresInDays} (1..365, null = no expiry) as an instant.
   *
   * @throws ApiException 400 {@code VALIDATION_FAILED}
   */
  public Instant expiry(Integer expiresInDays) {
    if (expiresInDays == null) {
      return null;
    }
    if (expiresInDays < 1 || expiresInDays > MAX_EXPIRES_IN_DAYS) {
      throw ApiException.validation("expiresInDays", "1-" + MAX_EXPIRES_IN_DAYS + " days, or omit for no expiry");
    }
    return time.now().plus(Duration.ofDays(expiresInDays));
  }

  static String validateName(String name) {
    String n = name == null ? null : name.trim();
    if (n == null || !NAME.matcher(n).matches()) {
      throw ApiException.validation("name", "1-64 characters: letters, digits, space and _ . : ( ) -");
    }
    return n;
  }

  // ------------------------------------------------------------------------------------------- read

  /** Every key of the player (revoked and expired included), newest first. */
  public List<ApiKeyInfoDto> list(long playerId) {
    return jdbc.sql("SELECT " + INFO_COLUMNS + " FROM api_key WHERE player_id = ? ORDER BY created_at DESC, id DESC")
        .param(playerId).query(ApiKeyService::info).list();
  }

  public ApiKeyInfoDto info(long keyRowId) {
    return jdbc.sql("SELECT " + INFO_COLUMNS + " FROM api_key WHERE id = ?").param(keyRowId)
        .query(ApiKeyService::info).single();
  }

  /** The issued key as the §18.3 {@code ApiKeyCreated} body: info, the one-time token, connection snippets. */
  public ApiKeyCreatedDto created(IssuedKey key) {
    return new ApiKeyCreatedDto(info(key.id()), key.token(), snippets(key.token()));
  }

  /** Keys neither revoked nor expired. */
  public int countActive(long playerId) {
    return jdbc.sql("""
            SELECT COUNT(*) FROM api_key
             WHERE player_id = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)""")
        .params(playerId, Jdbc.ts(time.now()))
        .query(Integer.class).single();
  }

  /** True if the player has a key that is neither revoked nor expired. */
  public boolean hasActiveKey(long playerId) {
    return countActive(playerId) > 0;
  }

  /** The owner of a key, if the key exists. */
  public java.util.Optional<Long> ownerOf(String keyId) {
    return jdbc.sql("SELECT player_id FROM api_key WHERE key_id = ?").param(keyId).query(Long.class).optional();
  }

  // ------------------------------------------------------------------------------------------- revoke

  /**
   * Revokes one key (idempotent: an already revoked key stays as it was). With {@code ownerOrNull}, only that
   * player's key matches (personal keys); null = any key (admin).
   *
   * @return the owner's player id
   * @throws ApiException 404 {@code NOT_FOUND}
   */
  public long revoke(String keyId, Long ownerOrNull, long byPlayerId) {
    Long owner = keyId == null || keyId.length() > 16 ? null : ownerOf(keyId).orElse(null);
    if (owner == null || (ownerOrNull != null && !owner.equals(ownerOrNull))) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "No such API key.");
    }
    jdbc.sql("UPDATE api_key SET revoked_at = ?, revoked_by = ? WHERE key_id = ? AND revoked_at IS NULL")
        .params(Jdbc.ts(time.now()), byPlayerId, keyId).update();
    evictNowAndAfterCommit(() -> keyCache.evict(keyId));
    return owner;
  }

  /** Revokes every live key of the player (password change, freeze with {@code revokeKeys}); returns the count. */
  public int revokeAll(long playerId, long byPlayerId) {
    int n = jdbc.sql("UPDATE api_key SET revoked_at = ?, revoked_by = ? WHERE player_id = ? AND revoked_at IS NULL")
        .params(Jdbc.ts(time.now()), byPlayerId, playerId).update();
    evictNowAndAfterCommit(() -> keyCache.evictPlayer(playerId));
    return n;
  }

  /**
   * Removes {@code scope} from every live key of the player (§10.5 "API trading revoked"); a key left with no scope
   * is revoked. Returns the number of keys changed.
   */
  public int dropScope(long playerId, String scope, long byPlayerId) {
    record Row(long id, String scopes) {}
    List<Row> rows = jdbc.sql("SELECT id, scopes FROM api_key WHERE player_id = ? AND revoked_at IS NULL")
        .param(playerId).query((rs, n) -> new Row(rs.getLong(1), rs.getString(2))).list();
    int changed = 0;
    for (Row r : rows) {
      Set<String> scopes = new TreeSet<>(Arrays.asList(r.scopes().trim().split("\\s+")));
      if (!scopes.remove(scope)) {
        continue;
      }
      if (scopes.isEmpty()) {
        jdbc.sql("UPDATE api_key SET revoked_at = ?, revoked_by = ? WHERE id = ?")
            .params(Jdbc.ts(time.now()), byPlayerId, r.id()).update();
      } else {
        jdbc.sql("UPDATE api_key SET scopes = ? WHERE id = ?").params(String.join(" ", scopes), r.id()).update();
      }
      changed++;
    }
    evictNowAndAfterCommit(() -> keyCache.evictPlayer(playerId));
    return changed;
  }

  /**
   * Evicts the key cache now and, when called inside a transaction (password change, freeze, PATCH), once more after
   * commit: a request authenticated between the eviction and the commit would otherwise re-cache the old row (still
   * live, still with {@code trade}) for the cache's TTL.
   */
  private void evictNowAndAfterCommit(Runnable evict) {
    evict.run();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          evict.run();
        }
      });
    }
  }

  // ------------------------------------------------------------------------------------------- snippets

  /** §20.4 connection recipes for a freshly issued token (shown once, with the token). */
  public ApiKeySnippetsDto snippets(String token) {
    String base = stripSlash(properties.publicUrl());
    String mcp = base + "/mcp";
    String claudeCode = "claude mcp add --transport http stocks " + mcp
        + " --header \"Authorization: Bearer " + token + "\" --header \"X-Stocks-Client: claude-code\"";
    String claudeApi = """
        {
          "headers": {"anthropic-beta": "mcp-client-2025-11-20"},
          "mcp_servers": [{"type": "url", "url": "%s", "name": "stocks", "authorization_token": "%s"}],
          "tools": [{"type": "mcp_toolset", "mcp_server_name": "stocks"}]
        }""".formatted(mcp, token);
    String openai = """
        {
          "tools": [{"type": "mcp", "server_label": "stocks", "server_url": "%s", "authorization": "%s",
                     "headers": {"X-Stocks-Client": "openai-responses"}, "require_approval": "never"}]
        }""".formatted(mcp, token);
    String curl = "curl -s -H \"Authorization: Bearer " + token + "\" " + base + "/api/v1/me";
    return new ApiKeySnippetsDto(claudeCode, claudeApi, openai, curl);
  }

  private static String stripSlash(String url) {
    String u = url == null ? "" : url.trim();
    while (u.endsWith("/")) {
      u = u.substring(0, u.length() - 1);
    }
    return u;
  }

  static ApiKeyInfoDto info(ResultSet rs, int rowNum) throws SQLException {
    List<String> scopes = Arrays.stream(rs.getString("scopes").trim().split("\\s+"))
        .filter(s -> !s.isEmpty()).sorted().toList();
    return new ApiKeyInfoDto(rs.getString("key_id"), rs.getString("name"), scopes, Jdbc.instant(rs, "created_at"),
        Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "last_used_at"), rs.getString("last_used_ip"),
        Jdbc.instant(rs, "revoked_at"));
  }
}
