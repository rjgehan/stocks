package com.stocks.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jwt.SignedJWT;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.oauth.cimd.CimdClients;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.WebSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Review findings on the OAuth server (§17.9), end to end through the real filter chains:
 * <ul>
 *   <li>the admin turning a human's API trading off removes {@code trade} from a connector acting as that human, at
 *       once and at every later refresh (§10.5, §17.9.3);</li>
 *   <li>a freeze with "revoke keys" ends the player's OAuth grants too, and the admin can list and revoke any
 *       player's grants (§21.1);</li>
 *   <li>approving a consent needs a recent password check, like issuing an API key (§17.9.3 step 4);</li>
 *   <li>the token and revoke endpoints refuse parameters in the URL (RFC 6749 §2.3.1);</li>
 *   <li>per-IP limits count an IPv6 client by its /64, registrations have a global hourly ceiling, and live pending
 *       authorization requests are capped (§17.9.1).</li>
 * </ul>
 * Production's DCR limits and a small pending-request cap are set here, so this class has its own context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, OAuthFlowIT.Documents.class})
@TestPropertySource(properties = {
    "stocks.oauth.dcr.per-ip-per-hour=20",
    "stocks.oauth.dcr.max-per-hour=30",
    "stocks.oauth.max-pending-requests=4"})
class OAuthAdminControlsIT {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired JsonMapper json;
  @Autowired CimdClients cimd;
  @Autowired OAuthRateLimiter limiter;

  Fixtures fx;
  Instant t0;
  Fixtures.PlayerRef alice;
  Fixtures.PlayerRef bot;
  Fixtures.PlayerRef admin;
  WebSession aliceSession;
  WebSession adminSession;
  OAuthTestClient claude;

  @BeforeEach
  void setUp() {
    fx = new Fixtures(jdbc, time);
    t0 = time.now();
    int n = SEQ.incrementAndGet();
    alice = fx.human("acalice" + n);
    bot = fx.ai("acbot" + n, alice);
    admin = fx.admin("acadmin" + n);
    fx.account(alice, "500.00");
    fx.account(bot, "500.00");
    aliceSession = WebSession.login(mvc, alice.handle(), Fixtures.PASSWORD);
    adminSession = WebSession.login(mvc, admin.handle(), Fixtures.PASSWORD);
    claude = new OAuthTestClient(mvc, json, OAuthFlowIT.CLAUDE, OAuthFlowIT.CLAUDE_CALLBACK);
    cimd.clear();
    limiter.reset();
    // every test starts below the pending-request cap
    jdbc.sql("UPDATE oauth_authorization_request SET consumed_at = ? WHERE consumed_at IS NULL")
        .param(Jdbc.ts(time.now())).update();
  }

  @AfterEach
  void resetClock() {
    time.set(t0);
  }

  JsonNode body(MvcResult r) {
    return json.readTree(WebSession.contentOf(r));
  }

  String scopeOf(String accessToken) throws Exception {
    return json.readTree(SignedJWT.parse(accessToken).getJWTClaimsSet().toString()).get("scope").asString();
  }

  JsonNode mcpResult(String access, String tool) throws Exception {
    return body(OAuthTestClient.mcpCall(mvc, access, tool, "{}")).get("result");
  }

  // ------------------------------------------------------------------------------------------- admin controls

  @Test
  void turningApiTradingOffRemovesTradeFromAConnectorActingAsTheHuman() throws Exception {
    jdbc.sql("UPDATE player SET api_trading_allowed = TRUE WHERE id = ?").param(alice.id()).update();
    JsonNode self = claude.connect(aliceSession, alice.handle(), null);
    JsonNode asBot = claude.connect(aliceSession, bot.handle(), null);
    assertThat(scopeOf(self.get("access_token").asString())).contains("trade");
    assertThat(mcpResult(self.get("access_token").asString(), "cancel_all_orders").get("isError").asBoolean())
        .isFalse();

    MvcResult off = adminSession.perform(patch("/api/v1/admin/players/" + alice.handle())
        .contentType(MediaType.APPLICATION_JSON).content("{\"apiTradingAllowed\":false}"));
    assertThat(off.getResponse().getStatus()).as(WebSession.contentOf(off)).isEqualTo(200);

    // the access token issued before the change loses trade on its next request
    JsonNode refused = mcpResult(self.get("access_token").asString(), "cancel_all_orders");
    assertThat(refused.get("isError").asBoolean()).isTrue();
    assertThat(refused.get("content").get(0).get("text").asString()).contains("FORBIDDEN_SCOPE");
    // and a refresh never brings it back
    JsonNode refreshed = body(claude.refresh(self.get("refresh_token").asString()));
    assertThat(refreshed.get("scope").asString()).isEqualTo("account:read league:read market:read");
    assertThat(mcpResult(refreshed.get("access_token").asString(), "cancel_all_orders").get("isError").asBoolean())
        .isTrue();
    String gid = json.readTree(SignedJWT.parse(self.get("access_token").asString()).getJWTClaimsSet().toString())
        .get("gid").asString();
    assertThat(jdbc.sql("SELECT scopes FROM oauth_grant WHERE public_id = ?").param(gid).query(String.class).single())
        .doesNotContain("trade");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'OAUTH_SCOPE_DROP' AND target_id = ?")
        .param(gid).query(Integer.class).single()).isEqualTo(1);
    // a grant acting as one of the human's bots keeps trading (a bot always trades)
    assertThat(mcpResult(asBot.get("access_token").asString(), "cancel_all_orders").get("isError").asBoolean())
        .isFalse();
    assertThat(body(claude.refresh(asBot.get("refresh_token").asString())).get("scope").asString())
        .contains("trade");
  }

  @Test
  void aFreezeWithRevokeKeysEndsTheOAuthGrantsAndTheAdminCanListAndRevokeThem() throws Exception {
    JsonNode t = claude.connect(aliceSession, bot.handle(), null);
    JsonNode other = claude.connect(aliceSession, bot.handle(), "market:read");

    // the admin sees the bot's connectors (acting as it) and the owner's (approved by her)
    JsonNode listed = body(adminSession.perform(get("/api/v1/admin/players/" + bot.handle() + "/oauth-grants")));
    assertThat(listed.size()).isEqualTo(2);
    JsonNode g = listed.get(0);
    assertThat(g.get("actAs").get("handle").asString()).isEqualTo(bot.handle());
    assertThat(g.get("approvedBy").get("handle").asString()).isEqualTo(alice.handle());
    assertThat(g.get("client").get("name").asString()).isEqualTo("Claude");
    assertThat(g.get("redirectHost").asString()).isEqualTo("claude.ai");
    assertThat(g.get("connected").asBoolean()).isTrue();
    assertThat(g.toString()).doesNotContain("ort_").doesNotContain("code_hash");
    assertThat(body(adminSession.perform(get("/api/v1/admin/players/" + alice.handle() + "/oauth-grants"))).size())
        .isEqualTo(2);

    // revoke one: it stops at once
    String otherGid = json.readTree(SignedJWT.parse(other.get("access_token").asString()).getJWTClaimsSet()
        .toString()).get("gid").asString();
    assertThat(adminSession.perform(delete("/api/v1/admin/oauth-grants/" + otherGid)).getResponse().getStatus())
        .isEqualTo(204);
    assertThat(OAuthTestClient.mcpCall(mvc, other.get("access_token").asString(), "whoami", "{}").getResponse()
        .getStatus()).isEqualTo(401);
    assertThat(adminSession.perform(delete("/api/v1/admin/oauth-grants/" + otherGid)).getResponse().getStatus())
        .as("already revoked").isEqualTo(404);
    assertThat(jdbc.sql("SELECT actor_player_id FROM audit_log WHERE action = 'OAUTH_REVOKE' AND target_id = ?")
        .param(otherGid).query(Long.class).single()).isEqualTo(admin.id());
    // a connector's token never reaches the admin API
    assertThat(mvc.perform(get("/api/v1/admin/players/" + bot.handle() + "/oauth-grants")
        .header("Authorization", "Bearer " + t.get("access_token").asString())).andReturn().getResponse()
        .getStatus()).isEqualTo(403);

    // the kill switch with "revoke keys": the remaining grant is gone, also after the unfreeze
    MvcResult frozen = adminSession.perform(post("/api/v1/admin/players/" + bot.handle() + "/freeze")
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"runaway\",\"revokeKeys\":true}"));
    assertThat(frozen.getResponse().getStatus()).as(WebSession.contentOf(frozen)).isEqualTo(200);
    assertThat(adminSession.perform(post("/api/v1/admin/players/" + bot.handle() + "/unfreeze")).getResponse()
        .getStatus()).isEqualTo(200);
    assertThat(body(claude.refresh(t.get("refresh_token").asString())).get("error").asString())
        .isEqualTo("invalid_grant");
    assertThat(OAuthTestClient.mcpCall(mvc, t.get("access_token").asString(), "whoami", "{}").getResponse()
        .getStatus()).isEqualTo(401);
    String gid = json.readTree(SignedJWT.parse(t.get("access_token").asString()).getJWTClaimsSet().toString())
        .get("gid").asString();
    assertThat(jdbc.sql("SELECT revoke_reason FROM oauth_grant WHERE public_id = ?").param(gid).query(String.class)
        .single()).isEqualTo("CREDENTIALS_CHANGED");
    assertThat(body(adminSession.perform(get("/api/v1/admin/players/" + bot.handle() + "/oauth-grants"))).size())
        .isZero();
  }

  @Test
  void aFreezeWithoutRevokeKeysKeepsTheGrantsForTheUnfreeze() throws Exception {
    JsonNode t = claude.connect(aliceSession, bot.handle(), null);
    adminSession.perform(post("/api/v1/admin/players/" + bot.handle() + "/freeze")
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"pause\"}"));
    assertThat(body(OAuthTestClient.mcpCall(mvc, t.get("access_token").asString(), "whoami", "{}")).get("code")
        .asString()).isEqualTo("PLAYER_FROZEN");
    adminSession.perform(post("/api/v1/admin/players/" + bot.handle() + "/unfreeze"));
    assertThat(OAuthTestClient.mcpCall(mvc, t.get("access_token").asString(), "whoami", "{}").getResponse()
        .getStatus()).isEqualTo(200);
  }

  // ------------------------------------------------------------------------------------------- recent auth

  @Test
  void approvingNeedsARecentPasswordCheckButDenyingDoesNot() throws Exception {
    time.advance(Duration.ofMinutes(20));                    // alice signed in 20 minutes ago
    OAuthTestClient.Attempt a = OAuthTestClient.attempt();
    String requestId = OAuthTestClient.requestId(claude.authorize(a, null, null));
    MvcResult stale = claude.decide(aliceSession, requestId,
        "{\"approve\":true,\"actAs\":\"" + bot.handle() + "\"}");
    assertThat(stale.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(stale).get("code").asString()).isEqualTo("RECENT_AUTH_REQUIRED");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM oauth_grant WHERE human_player_id = ?").param(alice.id())
        .query(Integer.class).single()).as("no grant, no code").isZero();

    // the password prompt, then the same approval works (the request survived the refusal)
    MvcResult reauth = aliceSession.perform(post("/api/v1/auth/reauth").contentType(MediaType.APPLICATION_JSON)
        .content("{\"password\":\"" + Fixtures.PASSWORD + "\"}"));
    assertThat(reauth.getResponse().getStatus()).as(WebSession.contentOf(reauth)).isIn(200, 204);
    String redirect = claude.approve(aliceSession, requestId, bot.handle(), null);
    assertThat(OAuthTestClient.query(redirect)).containsKey("code");

    // denying stays possible on an old session
    time.advance(Duration.ofMinutes(20));
    String other = OAuthTestClient.requestId(claude.authorize(OAuthTestClient.attempt(), null, null));
    MvcResult denied = claude.decide(aliceSession, other, "{\"approve\":false}");
    assertThat(denied.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(denied).get("redirectUrl").asString()).contains("error=access_denied");
  }

  // ------------------------------------------------------------------------------------------- the token endpoint

  @Test
  void theTokenAndRevokeEndpointsRefuseParametersInTheUrl() throws Exception {
    OAuthTestClient.Attempt a = OAuthTestClient.attempt();
    String requestId = OAuthTestClient.requestId(claude.authorize(a, null, null));
    Map<String, String> q = OAuthTestClient.query(claude.approve(aliceSession, requestId, bot.handle(), null));
    MvcResult inUrl = mvc.perform(post("/oauth2/token?grant_type=authorization_code&code=" + q.get("code")
            + "&code_verifier=" + a.verifier() + "&client_id=" + OAuthFlowIT.CLAUDE)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)).andReturn();
    assertThat(inUrl.getResponse().getStatus()).isEqualTo(400);
    assertThat(body(inUrl).get("error").asString()).isEqualTo("invalid_request");
    assertThat(WebSession.contentOf(inUrl)).doesNotContain("access_token");
    // the same parameters in the form body work (the code was not used up by the refused request)
    MvcResult inBody = claude.token(Map.of("grant_type", "authorization_code", "code", q.get("code"),
        "code_verifier", a.verifier()));
    assertThat(inBody.getResponse().getStatus()).as(WebSession.contentOf(inBody)).isEqualTo(200);
    String refresh = body(inBody).get("refresh_token").asString();
    MvcResult revokeInUrl = mvc.perform(post("/oauth2/revoke?token=" + refresh + "&client_id=" + OAuthFlowIT.CLAUDE)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)).andReturn();
    assertThat(revokeInUrl.getResponse().getStatus()).isEqualTo(400);
    assertThat(body(claude.refresh(refresh)).has("access_token")).as("not revoked").isTrue();
  }

  // ------------------------------------------------------------------------------------------- flood limits

  MvcResult register(String ip, String name) throws Exception {
    return mvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
        .content("{\"client_name\":\"" + name + "\",\"redirect_uris\":[\"https://claude.ai/api/mcp/auth_callback\"],"
            + "\"token_endpoint_auth_method\":\"none\"}")
        .with(r -> {
          r.setRemoteAddr(ip);
          return r;
        })).andReturn();
  }

  @Test
  void anIpv6NetworkCountsAsOneAddressAndRegistrationsHaveAGlobalCeiling() throws Exception {
    for (int i = 1; i <= 20; i++) {
      assertThat(register("2001:db8:1:2::" + Integer.toHexString(i), "flood").getResponse().getStatus())
          .isEqualTo(201);
    }
    // a fresh address in the same /64 is the same client
    MvcResult sameNetwork = register("2001:db8:1:2:ffff:ffff:ffff:fffe", "flood");
    assertThat(sameNetwork.getResponse().getStatus()).isEqualTo(429);
    assertThat(body(sameNetwork).get("error").asString()).isEqualTo("slow_down");
    // another /64 is another client, until the global hourly ceiling (30 here)
    for (int i = 1; i <= 10; i++) {
      assertThat(register("2001:db8:1:" + Integer.toHexString(100 + i) + "::1", "other").getResponse().getStatus())
          .isEqualTo(201);
    }
    MvcResult ceiling = register("198.51.100.7", "legit");
    assertThat(ceiling.getResponse().getStatus()).isEqualTo(503);
    assertThat(body(ceiling).get("error").asString()).isEqualTo("temporarily_unavailable");
    time.advance(Duration.ofHours(1));
    assertThat(register("198.51.100.7", "legit").getResponse().getStatus()).isEqualTo(201);
  }

  @Test
  void livePendingAuthorizationRequestsAreCapped() throws Exception {
    for (int i = 0; i < 4; i++) {
      OAuthTestClient.requestId(claude.authorize(OAuthTestClient.attempt(), null, null));
    }
    OAuthTestClient.Attempt a = OAuthTestClient.attempt();
    MvcResult full = claude.authorize(a, null, null);
    assertThat(full.getResponse().getStatus()).isEqualTo(302);
    Map<String, String> q = OAuthTestClient.query(full.getResponse().getHeader("Location"));
    assertThat(q).containsEntry("error", "temporarily_unavailable").containsEntry("state", a.state());
    assertThat(full.getResponse().getHeader("Location")).startsWith(OAuthFlowIT.CLAUDE_CALLBACK);
    // expired requests do not count
    time.advance(Duration.ofMinutes(16));
    OAuthTestClient.requestId(claude.authorize(OAuthTestClient.attempt(), null, null));
  }
}
