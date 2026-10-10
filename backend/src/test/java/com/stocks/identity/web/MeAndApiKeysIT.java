package com.stocks.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.stocks.contract.security.Scopes;
import com.stocks.security.PlayerSessions;
import com.stocks.testkit.ContractMocksWebTest;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.WebSession;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** §18.1 {@code PATCH /me}, {@code POST /me/password} and personal API keys (§17.2), over the real chains. */
class MeAndApiKeysIT extends ContractMocksWebTest {

  @Autowired PlayerSessions sessions;

  static MvcResult send(WebSession s, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b,
                        String json) {
    return s.perform(b.contentType(MediaType.APPLICATION_JSON).content(json));
  }

  @Test
  void patchMeChangesTheDisplayNameAndMergesPreferences() {
    Fixtures.PlayerRef p = fx.human(handle("pat"));
    WebSession s = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);
    MvcResult r = send(s, patch("/api/v1/me"), "{\"displayName\":\"Pat Q.\",\"preferences\":{\"theme\":\"LIGHT\"}}");
    assertThat(status(r)).as(body(r)).isEqualTo(200);
    assertThat((String) json(r, "$.displayName")).isEqualTo("Pat Q.");
    assertThat((String) json(r, "$.preferences.theme")).isEqualTo("LIGHT");
    assertThat((String) json(r, "$.preferences.upDown")).isEqualTo("GREEN_UP");

    MvcResult r2 = send(s, patch("/api/v1/me"), "{\"preferences\":{\"upDown\":\"red_up\",\"confirmOrders\":false}}");
    assertThat((String) json(r2, "$.preferences.theme")).as("kept").isEqualTo("LIGHT");
    assertThat((String) json(r2, "$.preferences.upDown")).isEqualTo("RED_UP");
    assertThat((Boolean) json(r2, "$.preferences.confirmOrders")).isFalse();
    assertThat((String) json(r2, "$.displayName")).isEqualTo("Pat Q.");

    assertThat(code(send(s, patch("/api/v1/me"), "{\"displayName\":\"x\"}"))).isEqualTo("VALIDATION_FAILED");
    assertThat(code(send(s, patch("/api/v1/me"), "{\"preferences\":{\"theme\":\"PINK\"}}")))
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void patchMeAndPasswordAreSessionOnly() throws Exception {
    Fixtures.PlayerRef p = fx.human(handle("keyonly"));
    String key = fx.apiKey(p, pepper());
    MvcResult r = mvc.perform(patch("/api/v1/me").header("Authorization", "Bearer " + key)
        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Hacker\"}")).andReturn();
    assertThat(status(r)).isEqualTo(403);
    assertThat(code(r)).isEqualTo("FORBIDDEN");
    MvcResult keys = mvc.perform(get("/api/v1/me/api-keys").header("Authorization", "Bearer " + key)).andReturn();
    assertThat(status(keys)).isEqualTo(403);
  }

  @Test
  void passwordChangeEndsOtherSessionsAndRevokesKeys() {
    Fixtures.PlayerRef p = fx.human(handle("pw"));
    String key = fx.apiKey(p, Scopes.READ_ONLY, pepper());
    WebSession laptop = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);
    WebSession phone = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);
    assertThat(sessions.count(p.publicId())).isEqualTo(2);

    MvcResult wrong = send(laptop, post("/api/v1/me/password"),
        "{\"currentPassword\":\"not-my-password\",\"newPassword\":\"brand-new-password\"}");
    assertThat(code(wrong)).isEqualTo("BAD_CREDENTIALS");
    MvcResult weak = send(laptop, post("/api/v1/me/password"),
        "{\"currentPassword\":\"" + Fixtures.PASSWORD + "\",\"newPassword\":\"short\"}");
    assertThat(code(weak)).isEqualTo("WEAK_PASSWORD");

    String oldSid = laptop.sessionId();
    MvcResult ok = send(laptop, post("/api/v1/me/password"),
        "{\"currentPassword\":\"" + Fixtures.PASSWORD + "\",\"newPassword\":\"brand-new-password\"}");
    assertThat(status(ok)).as(body(ok)).isEqualTo(204);
    assertThat(laptop.sessionId()).as("rotated").isNotEqualTo(oldSid);
    assertThat(status(laptop.perform(get("/api/v1/me")))).as("this session lives on").isEqualTo(200);
    assertThat(status(phone.perform(get("/api/v1/me")))).as("the other one is gone").isEqualTo(401);
    MvcResult revoked = bearer(key);
    assertThat(code(revoked)).isEqualTo("API_KEY_REVOKED");
    assertThat(WebSession.anonymous(mvc).login(p.handle(), Fixtures.PASSWORD).getResponse().getStatus())
        .isEqualTo(401);
    assertThat(WebSession.anonymous(mvc).login(p.handle(), "brand-new-password").getResponse().getStatus())
        .isEqualTo(200);
  }

  MvcResult bearer(String key) {
    try {
      return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + key)).andReturn();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void personalKeysAreReadOnlyByDefaultAndTradeNeedsTheAdmin() {
    Fixtures.PlayerRef p = fx.human(handle("dev"));
    WebSession s = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);

    MvcResult created = send(s, post("/api/v1/me/api-keys"), "{\"name\":\"my script\",\"expiresInDays\":30}");
    assertThat(status(created)).as(body(created)).isEqualTo(201);
    String token = json(created, "$.token");
    assertThat(token).matches("^stk_[a-z2-7]{12}_[A-Za-z0-9_-]{43}$");
    List<String> scopes = json(created, "$.key.scopes");
    assertThat(scopes).containsExactly("account:read", "league:read", "market:read");
    assertThat((String) json(created, "$.key.expiresAt")).isNotNull();
    assertThat((String) json(created, "$.snippets.claudeCode")).contains("claude mcp add --transport http stocks")
        .contains("/mcp").contains(token);
    assertThat((String) json(created, "$.snippets.curl")).contains("Authorization: Bearer " + token);
    assertThat((String) json(created, "$.snippets.openaiResponses")).contains("\"server_label\": \"stocks\"");
    assertThat((String) json(created, "$.snippets.claudeApi")).contains("mcp-client-2025-11-20");

    MvcResult me = bearer(token);
    assertThat(status(me)).isEqualTo(200);
    assertThat((String) json(me, "$.authMethod")).isEqualTo("API_KEY");

    MvcResult trade = send(s, post("/api/v1/me/api-keys"), "{\"name\":\"trader\",\"scopes\":[\"trade\"]}");
    assertThat(status(trade)).isEqualTo(403);
    assertThat(code(trade)).isEqualTo("FORBIDDEN_SCOPE");
    jdbc.sql("UPDATE player SET api_trading_allowed = TRUE WHERE id = ?").param(p.id()).update();
    MvcResult trade2 = send(s, post("/api/v1/me/api-keys"),
        "{\"name\":\"trader\",\"scopes\":[\"trade\",\"account:read\"]}");
    assertThat(status(trade2)).as(body(trade2)).isEqualTo(201);

    assertThat(code(send(s, post("/api/v1/me/api-keys"), "{\"name\":\"x\",\"scopes\":[\"admin\"]}")))
        .isEqualTo("VALIDATION_FAILED");
    assertThat(code(send(s, post("/api/v1/me/api-keys"), "{\"name\":\"x\",\"expiresInDays\":400}")))
        .isEqualTo("VALIDATION_FAILED");
    assertThat(code(send(s, post("/api/v1/me/api-keys"), "{\"name\":\"\"}"))).isEqualTo("VALIDATION_FAILED");

    MvcResult list = s.perform(get("/api/v1/me/api-keys"));
    assertThat(status(list)).isEqualTo(200);
    List<String> ids = json(list, "$[*].keyId");
    assertThat(ids).hasSize(2);
    assertThat(body(list)).doesNotContain(token.substring(18));

    String keyId = json(created, "$.key.keyId");
    assertThat(status(s.perform(delete("/api/v1/me/api-keys/" + keyId)))).isEqualTo(204);
    assertThat(code(bearer(token))).isEqualTo("API_KEY_REVOKED");
    assertThat(status(s.perform(delete("/api/v1/me/api-keys/" + keyId)))).as("idempotent").isEqualTo(204);

    Fixtures.PlayerRef other = fx.human(handle("other"));
    String othersKey = fx.apiKey(other, Set.of(Scopes.MARKET_READ), pepper());
    String othersKeyId = othersKey.substring(4, 16);
    MvcResult notMine = s.perform(delete("/api/v1/me/api-keys/" + othersKeyId));
    assertThat(status(notMine)).isEqualTo(404);
    assertThat(status(bearer(othersKey))).as("untouched").isEqualTo(200);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE category = 'KEY' AND actor_player_id = ?")
        .param(p.id()).query(Long.class).single()).as("2 issues, 2 revokes").isEqualTo(4);
  }
}
