package com.stocks.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.identity.dto.ApiKeyInfoDto;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.security.ApiKeyAuthenticationException;
import com.stocks.security.ApiKeyAuthenticationProvider;
import com.stocks.security.ApiKeyAuthenticationToken;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestProperties;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §17.2 API keys: issue, list, revoke, scope removal and the verification cache (TTL 60 s here, as in production),
 * on a fresh database (embedded PostgreSQL under -Ppg-it).
 */
class ApiKeyServiceTest {

  TestDb db;
  MutableTimeSource time;
  StocksProperties props;
  PlayerQueriesImpl players;
  ApiKeyAuthenticationProvider provider;
  ApiKeyService keys;
  Fixtures fx;

  @org.junit.jupiter.api.AfterEach
  void closeDb() {
    db.close();
  }

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
    props = TestProperties.stocks("stocks.public-url", "https://stocks.gehan.cloud",
        "stocks.security.api-key-cache-ttl", "60s", "stocks.security.player-status-cache-ttl", "60s");
    players = new PlayerQueriesImpl(db.jdbc(), props);
    provider = new ApiKeyAuthenticationProvider(db.jdbc(), time, props, players);
    keys = new ApiKeyService(db.jdbc(), time, props, provider);
    fx = new Fixtures(db.jdbc(), time);
  }

  PlayerPrincipal auth(String token) {
    return (PlayerPrincipal) provider.authenticate(new ApiKeyAuthenticationToken(token)).getPrincipal();
  }

  ErrorCode authError(String token) {
    try {
      auth(token);
      return null;
    } catch (ApiKeyAuthenticationException e) {
      return e.code();
    }
  }

  @Test
  void issueListAndSnippets() {
    Fixtures.PlayerRef bot = fx.ai("claude", fx.admin("root"));
    ApiKeyService.IssuedKey k = keys.issue(bot.id(), "runner", Scopes.ALL, null, bot.id());
    assertThat(k.token()).matches("^stk_[a-z2-7]{12}_[A-Za-z0-9_-]{43}$").startsWith("stk_" + k.keyId() + "_");
    PlayerPrincipal p = auth(k.token());
    assertThat(p.handle()).isEqualTo("claude");
    assertThat(p.authMethod()).isEqualTo(AuthMethod.API_KEY);
    assertThat(p.apiKeyId()).isEqualTo(k.id());
    List<ApiKeyInfoDto> list = keys.list(bot.id());
    assertThat(list).singleElement().satisfies(i -> {
      assertThat(i.keyId()).isEqualTo(k.keyId());
      assertThat(i.scopes()).containsExactly("account:read", "league:read", "market:read", "trade");
      assertThat(i.revokedAt()).isNull();
    });
    var created = keys.created(k);
    assertThat(created.snippets().claudeCode()).isEqualTo("claude mcp add --transport http stocks "
        + "https://stocks.gehan.cloud/mcp --header \"Authorization: Bearer " + k.token()
        + "\" --header \"X-Stocks-Client: claude-code\"");
    assertThat(created.snippets().curl()).contains("https://stocks.gehan.cloud/api/v1/me");
    assertThat(keys.countActive(bot.id())).isEqualTo(1);
  }

  @Test
  void validation() {
    long id = fx.human("alice").id();
    assertThatThrownBy(() -> keys.issue(id, "", Scopes.READ_ONLY, null, id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> keys.issue(id, "x".repeat(65), Scopes.READ_ONLY, null, id))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> keys.issue(id, "ok", Set.of(), null, id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> keys.issue(id, "ok", Set.of("admin"), null, id)).isInstanceOf(ApiException.class);
    assertThat(ApiKeyService.parseScopes(null, Scopes.READ_ONLY)).isEqualTo(Scopes.READ_ONLY);
    assertThat(ApiKeyService.parseScopes(List.of(" TRADE "), Scopes.READ_ONLY)).containsExactly("trade");
    assertThatThrownBy(() -> ApiKeyService.parseScopes(List.of("admin"), Scopes.ALL))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    assertThat(keys.expiry(null)).isNull();
    assertThat(keys.expiry(30)).isEqualTo(TestTimes.START.plus(Duration.ofDays(30)));
    assertThatThrownBy(() -> keys.expiry(0)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> keys.expiry(366)).isInstanceOf(ApiException.class);
    for (int i = 0; i < ApiKeyService.MAX_ACTIVE_KEYS; i++) {
      keys.issue(id, "k" + i, Scopes.READ_ONLY, null, id);
    }
    assertThatThrownBy(() -> keys.issue(id, "one too many", Scopes.READ_ONLY, null, id))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void revocationIsImmediateDespiteTheCache() {
    Fixtures.PlayerRef bot = fx.ai("gpt", fx.admin("root"));
    ApiKeyService.IssuedKey k = keys.issue(bot.id(), "runner", Scopes.ALL, null, bot.id());
    assertThat(auth(k.token()).handle()).isEqualTo("gpt"); // now cached
    // a row changed behind the service's back stays cached until the TTL ...
    db.jdbc().sql("UPDATE api_key SET scopes = 'market:read' WHERE id = ?").param(k.id()).update();
    assertThat(auth(k.token()).scopes()).hasSize(4);
    time.advance(Duration.ofSeconds(1));
    // ... but the service evicts on revoke
    assertThatThrownBy(() -> keys.revoke(k.keyId(), 999L, bot.id())).as("not the owner")
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    assertThat(keys.revoke(k.keyId(), bot.id(), bot.id())).isEqualTo(bot.id());
    assertThat(authError(k.token())).isEqualTo(ErrorCode.API_KEY_REVOKED);
    assertThat(keys.list(bot.id()).get(0).revokedAt()).isNotNull();
    assertThatThrownBy(() -> keys.revoke("doesnotexist", null, bot.id())).isInstanceOf(ApiException.class);
  }

  @Test
  void statusChangesNeedTheStatusCacheEvicted() {
    Fixtures.PlayerRef bot = fx.ai("bot", fx.admin("root"));
    ApiKeyService.IssuedKey k = keys.issue(bot.id(), "runner", Scopes.ALL, null, bot.id());
    assertThat(authError(k.token())).isNull();
    fx.status(bot, "FROZEN");
    assertThat(authError(k.token())).as("cached ACTIVE").isNull();
    players.evict(bot.id());
    assertThat(authError(k.token())).isEqualTo(ErrorCode.PLAYER_FROZEN);
  }

  @Test
  void revokeAllAndDropScope() {
    Fixtures.PlayerRef h = fx.human("trader");
    ApiKeyService.IssuedKey both = keys.issue(h.id(), "both", Set.of(Scopes.TRADE, Scopes.MARKET_READ), null, h.id());
    ApiKeyService.IssuedKey tradeOnly = keys.issue(h.id(), "trade", Set.of(Scopes.TRADE), null, h.id());
    ApiKeyService.IssuedKey readOnly = keys.issue(h.id(), "read", Scopes.READ_ONLY, null, h.id());
    auth(both.token());
    auth(tradeOnly.token());
    assertThat(keys.dropScope(h.id(), Scopes.TRADE, h.id())).isEqualTo(2);
    assertThat(auth(both.token()).scopes()).containsExactly(Scopes.MARKET_READ);
    assertThat(authError(tradeOnly.token())).isEqualTo(ErrorCode.API_KEY_REVOKED);
    assertThat(auth(readOnly.token()).scopes()).isEqualTo(Scopes.READ_ONLY);

    assertThat(keys.revokeAll(h.id(), h.id())).isEqualTo(2);
    assertThat(authError(both.token())).isEqualTo(ErrorCode.API_KEY_REVOKED);
    assertThat(authError(readOnly.token())).isEqualTo(ErrorCode.API_KEY_REVOKED);
    assertThat(keys.countActive(h.id())).isZero();
  }

  @Test
  void expiredKeysDoNotCount() {
    long id = fx.human("temp").id();
    ApiKeyService.IssuedKey k = keys.issue(id, "short", Scopes.READ_ONLY, Instant.parse("2026-10-10T00:00:00Z"), id);
    assertThat(keys.countActive(id)).isEqualTo(1);
    time.advance(Duration.ofDays(1));
    assertThat(keys.countActive(id)).isZero();
    assertThat(authError(k.token())).isEqualTo(ErrorCode.API_KEY_EXPIRED);
  }
}
