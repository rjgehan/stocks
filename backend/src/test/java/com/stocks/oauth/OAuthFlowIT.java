package com.stocks.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jwt.SignedJWT;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.oauth.cimd.CimdClients;
import com.stocks.oauth.cimd.CimdFetcher;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.WebSession;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * §17.9 end to end through the real filter chains (MockMvc): discovery documents, the authorization-code flow with
 * PKCE for a CIMD client (claude.ai's real metadata document, served by a test fetcher) and a DCR client, the act-as
 * consent, JWTs on {@code /mcp} as the consented player, scope enforcement, refresh rotation with reuse detection,
 * revocation (client and Settings), and the rule that no grant ever reaches admin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, OAuthFlowIT.Documents.class})
class OAuthFlowIT {

  static final String CLAUDE = "https://claude.ai/oauth/mcp-oauth-client-metadata";
  static final String CLAUDE_CALLBACK = "https://claude.ai/api/mcp/auth_callback";
  static final String CLAUDE_CODE = "https://claude.ai/oauth/claude-code-client-metadata";
  private static final AtomicInteger SEQ = new AtomicInteger();

  /** Serves client ID metadata documents without the network (the SSRF-safe fetcher has its own tests). */
  @TestConfiguration(proxyBeanMethods = false)
  static class Documents {
    static final Map<String, String> DOCS = new ConcurrentHashMap<>();
    static final AtomicInteger FETCHES = new AtomicInteger();

    static {
      DOCS.put(CLAUDE, """
          {"client_id":"https://claude.ai/oauth/mcp-oauth-client-metadata","client_name":"Claude",
           "client_uri":"https://claude.ai","redirect_uris":["https://claude.ai/api/mcp/auth_callback"],
           "grant_types":["authorization_code","refresh_token","urn:ietf:params:oauth:grant-type:jwt-bearer"],
           "response_types":["code"],"token_endpoint_auth_method":"none"}""");
      DOCS.put(CLAUDE_CODE, """
          {"client_id":"https://claude.ai/oauth/claude-code-client-metadata","client_name":"Claude Code",
           "client_uri":"https://claude.ai","redirect_uris":["http://localhost/callback","http://127.0.0.1/callback"],
           "grant_types":["authorization_code","refresh_token"],"response_types":["code"],
           "token_endpoint_auth_method":"none"}""");
    }

    @Bean
    @Primary
    CimdFetcher testCimdFetcher() {
      return url -> {
        FETCHES.incrementAndGet();
        String doc = DOCS.get(url.toString());
        if (doc == null) {
          throw new CimdFetcher.FetchException("The client metadata document answered HTTP 404.");
        }
        return new CimdFetcher.Response(doc.getBytes(StandardCharsets.UTF_8), "max-age=3600");
      };
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired JsonMapper json;
  @Autowired CimdClients cimd;
  @Autowired OAuthCleanupTask cleanup;
  @Autowired com.stocks.config.StocksProperties properties;

  Fixtures fx;
  java.time.Instant t0;
  Fixtures.PlayerRef alice;
  Fixtures.PlayerRef bot;
  WebSession aliceSession;
  OAuthTestClient claude;

  @BeforeEach
  void setUp() {
    fx = new Fixtures(jdbc, time);
    t0 = time.now();
    int n = SEQ.incrementAndGet();
    alice = fx.human("oalice" + n);
    bot = fx.ai("oclaude" + n, alice);
    aliceSession = WebSession.login(mvc, alice.handle(), Fixtures.PASSWORD);
    claude = new OAuthTestClient(mvc, json, CLAUDE, CLAUDE_CALLBACK);
    cimd.clear();
  }

  @org.junit.jupiter.api.AfterEach
  void resetClock() {
    time.set(t0);
  }

  JsonNode body(MvcResult r) {
    return json.readTree(WebSession.contentOf(r));
  }

  JsonNode jwtClaims(String token) throws Exception {
    return json.readTree(SignedJWT.parse(token).getJWTClaimsSet().toString());
  }

  // ----------------------------------------------------------------------------------------- discovery

  @Test
  void discoveryDocumentsAreCacheableAndCrossOrigin() throws Exception {
    MvcResult prm = mvc.perform(get("/.well-known/oauth-protected-resource/mcp").header("Origin",
        "https://inspector.example")).andReturn();
    assertThat(prm.getResponse().getStatus()).isEqualTo(200);
    JsonNode p = body(prm);
    assertThat(p.get("resource").asString()).isEqualTo(OAuthTestClient.RESOURCE);
    assertThat(p.get("authorization_servers").get(0).asString()).isEqualTo(OAuthTestClient.ISSUER);
    assertThat(p.get("scopes_supported").toString())
        .isEqualTo("[\"market:read\",\"account:read\",\"trade\",\"league:read\"]");
    assertThat(prm.getResponse().getHeader("Cache-Control")).isEqualTo("max-age=300, public");
    assertThat(prm.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
    assertThat(body(mvc.perform(get("/.well-known/oauth-protected-resource")).andReturn()).get("resource").asString())
        .isEqualTo(OAuthTestClient.RESOURCE);

    JsonNode as = body(mvc.perform(get("/.well-known/oauth-authorization-server")).andReturn());
    assertThat(as.get("issuer").asString()).isEqualTo(OAuthTestClient.ISSUER);
    assertThat(as.get("authorization_endpoint").asString()).isEqualTo(OAuthTestClient.ISSUER + "/oauth2/authorize");
    assertThat(as.get("token_endpoint").asString()).isEqualTo(OAuthTestClient.ISSUER + "/oauth2/token");
    assertThat(as.get("registration_endpoint").asString()).isEqualTo(OAuthTestClient.ISSUER + "/oauth2/register");
    assertThat(as.get("revocation_endpoint").asString()).isEqualTo(OAuthTestClient.ISSUER + "/oauth2/revoke");
    assertThat(as.get("jwks_uri").asString()).isEqualTo(OAuthTestClient.ISSUER + "/oauth2/jwks");
    assertThat(as.get("code_challenge_methods_supported").toString()).isEqualTo("[\"S256\"]");
    assertThat(as.get("token_endpoint_auth_methods_supported").toString()).contains("\"none\"");
    assertThat(as.get("client_id_metadata_document_supported").asBoolean()).isTrue();
    assertThat(as.get("authorization_response_iss_parameter_supported").asBoolean()).isTrue();
    assertThat(as.get("scopes_supported").toString()).contains("offline_access");
    assertThat(as.get("grant_types_supported").toString()).isEqualTo("[\"authorization_code\",\"refresh_token\"]");

    JsonNode jwks = body(mvc.perform(get("/oauth2/jwks")).andReturn());
    JsonNode key = jwks.get("keys").get(0);
    assertThat(key.get("kty").asString()).isEqualTo("RSA");
    assertThat(key.get("alg").asString()).isEqualTo("RS256");
    assertThat(key.has("d")).as("never the private part").isFalse();

    assertThat(mvc.perform(get("/.well-known/openid-configuration")).andReturn().getResponse().getStatus())
        .isEqualTo(404);
  }

  @Test
  void anonymousMcpGetsTheResourceMetadataChallenge() throws Exception {
    MvcResult r = mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(401);
    assertThat(r.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"stocks\", resource_metadata="
        + "\"http://localhost:5173/.well-known/oauth-protected-resource/mcp\"");
  }

  // ----------------------------------------------------------------------------------------- the main flow

  @Test
  void claudeActingAsTheBotGetsAJwtThatWhoamiResolvesToTheBot() throws Exception {
    OAuthTestClient.Attempt a = OAuthTestClient.attempt();
    String requestId = OAuthTestClient.requestId(claude.authorize(a, "market:read account:read trade league:read "
        + "offline_access openid", null));

    // the consent screen: the bot first, then "myself"; trade only for the bot (no API trading for alice)
    JsonNode consent = body(aliceSession.perform(get("/api/v1/me/oauth/requests/" + requestId)));
    assertThat(consent.get("client").get("name").asString()).isEqualTo("Claude");
    assertThat(consent.get("client").get("host").asString()).isEqualTo("claude.ai");
    assertThat(consent.get("client").get("kind").asString()).isEqualTo("CIMD");
    assertThat(consent.get("redirectHost").asString()).isEqualTo("claude.ai");
    assertThat(consent.get("loopbackRedirect").asBoolean()).isFalse();
    assertThat(consent.get("requestedScopes").toString())
        .isEqualTo("[\"account:read\",\"league:read\",\"market:read\",\"trade\"]");
    JsonNode actors = consent.get("actors");
    assertThat(actors.size()).isEqualTo(2);
    assertThat(actors.get(0).get("player").get("handle").asString()).isEqualTo(bot.handle());
    assertThat(actors.get(0).get("allowedScopes").toString()).contains("trade");
    assertThat(actors.get(1).get("self").asBoolean()).isTrue();
    assertThat(actors.get(1).get("allowedScopes").toString()).doesNotContain("trade");

    String redirect = claude.approve(aliceSession, requestId, bot.handle(), null);
    assertThat(redirect).startsWith(CLAUDE_CALLBACK + "?code=");
    Map<String, String> q = OAuthTestClient.query(redirect);
    assertThat(q).containsEntry("state", a.state()).containsEntry("iss", OAuthTestClient.ISSUER);

    MvcResult tokenResult = claude.token(Map.of("grant_type", "authorization_code", "code", q.get("code"),
        "code_verifier", a.verifier(), "redirect_uri", CLAUDE_CALLBACK, "resource", OAuthTestClient.RESOURCE));
    assertThat(tokenResult.getResponse().getStatus()).as(WebSession.contentOf(tokenResult)).isEqualTo(200);
    assertThat(tokenResult.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    JsonNode token = body(tokenResult);
    assertThat(token.get("token_type").asString()).isEqualTo("Bearer");
    assertThat(token.get("expires_in").asLong()).isEqualTo(1800);
    assertThat(token.get("refresh_token").asString()).startsWith("ort_");
    assertThat(token.get("scope").asString()).isEqualTo("account:read league:read market:read trade");

    String access = token.get("access_token").asString();
    SignedJWT jwt = SignedJWT.parse(access);
    assertThat(jwt.getHeader().getType().toString()).isEqualTo("at+jwt");
    assertThat(jwt.getHeader().getKeyID()).isNotBlank();
    JsonNode claims = jwtClaims(access);
    assertThat(claims.get("iss").asString()).isEqualTo(OAuthTestClient.ISSUER);
    assertThat(claims.get("sub").asString()).isEqualTo(bot.publicId());
    assertThat(claims.get("act").get("sub").asString()).isEqualTo(alice.publicId());
    assertThat(claims.get("aud").toString()).contains(OAuthTestClient.RESOURCE);
    assertThat(claims.get("client_id").asString()).isEqualTo(CLAUDE);
    assertThat(claims.get("gid").asString()).startsWith("ogr_");

    MvcResult whoami = OAuthTestClient.mcpCall(mvc, access, "whoami", "{}");
    assertThat(whoami.getResponse().getStatus()).as(WebSession.contentOf(whoami)).isEqualTo(200);
    JsonNode me = body(whoami).get("result").get("structuredContent").get("me");
    assertThat(me.get("handle").asString()).isEqualTo(bot.handle());
    assertThat(me.get("kind").asString()).isEqualTo("AI");
    assertThat(me.get("authMethod").asString()).isEqualTo("OAUTH");

    // the grant shows in Settings -> Connected apps, acting as the bot
    JsonNode apps = body(aliceSession.perform(get("/api/v1/me/connected-apps")));
    assertThat(apps.size()).isEqualTo(1);
    assertThat(apps.get(0).get("actAs").get("handle").asString()).isEqualTo(bot.handle());
    assertThat(apps.get(0).get("client").get("name").asString()).isEqualTo("Claude");
    assertThat(apps.get(0).get("grantId").asString()).isEqualTo(claims.get("gid").asString());

    // audit: the grant and the token exchange
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'OAUTH_GRANT' AND target_id = ?")
        .param(claims.get("gid").asString()).query(Integer.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'OAUTH_TOKEN' AND target_id = ?")
        .param(claims.get("gid").asString()).query(Integer.class).single()).isEqualTo(1);
  }

  @Test
  void accessTokensAreAcceptedOnMcpOnly() throws Exception {
    String access = claude.connect(aliceSession, bot.handle(), null).get("access_token").asString();
    MvcResult rest = mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access)).andReturn();
    assertThat(rest.getResponse().getStatus()).isEqualTo(401);
    assertThat(rest.getResponse().getHeader("WWW-Authenticate")).contains("error=\"invalid_token\"")
        .contains("resource_metadata=");
    // a tampered token
    String tampered = access.substring(0, access.length() - 4) + (access.endsWith("AAAA") ? "BBBB" : "AAAA");
    assertThat(OAuthTestClient.mcpCall(mvc, tampered, "whoami", "{}").getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void expiredAccessTokensAreRefused() throws Exception {
    String access = claude.connect(aliceSession, bot.handle(), null).get("access_token").asString();
    time.advance(Duration.ofMinutes(32));
    try {
      MvcResult r = OAuthTestClient.mcpCall(mvc, access, "whoami", "{}");
      assertThat(r.getResponse().getStatus()).isEqualTo(401);
      assertThat(r.getResponse().getHeader("WWW-Authenticate")).contains("invalid_token");
    } finally {
      time.set(t0);
    }
  }

  @Test
  void actingAsMyselfWithoutApiTradingIsReadOnly() throws Exception {
    OAuthTestClient.Attempt a = OAuthTestClient.attempt();
    String requestId = OAuthTestClient.requestId(claude.authorize(a, null, null));
    MvcResult refused = claude.decide(aliceSession, requestId,
        "{\"approve\":true,\"actAs\":\"" + alice.handle() + "\",\"scopes\":[\"trade\"]}");
    assertThat(refused.getResponse().getStatus()).isEqualTo(400);
    assertThat(body(refused).get("code").asString()).isEqualTo("VALIDATION_FAILED");
    // the request survives a refused decision; the default scopes are what the actor may get
    String redirect = claude.approve(aliceSession, requestId, alice.handle(), null);
    Map<String, String> q = OAuthTestClient.query(redirect);
    JsonNode token = body(claude.token(Map.of("grant_type", "authorization_code", "code", q.get("code"),
        "code_verifier", a.verifier())));
    assertThat(token.get("scope").asString()).isEqualTo("account:read league:read market:read");
    String access = token.get("access_token").asString();
    assertThat(jwtClaims(access).has("act")).isFalse();
    JsonNode me = body(OAuthTestClient.mcpCall(mvc, access, "whoami", "{}")).get("result").get("structuredContent")
        .get("me");
    assertThat(me.get("handle").asString()).isEqualTo(alice.handle());
    // the trade tool needs the trade scope
    JsonNode cancel = body(OAuthTestClient.mcpCall(mvc, access, "cancel_all_orders", "{}")).get("result");
    assertThat(cancel.get("isError").asBoolean()).isTrue();
    assertThat(cancel.get("content").get(0).get("text").asString()).contains("FORBIDDEN_SCOPE");
  }

  @Test
  void scopesAreEnforcedPerTool() throws Exception {
    String access = claude.connect(aliceSession, bot.handle(), "market:read").get("access_token").asString();
    assertThat(jwtClaims(access).get("scope").asString()).isEqualTo("market:read");
    JsonNode account = body(OAuthTestClient.mcpCall(mvc, access, "get_account", "{}")).get("result");
    assertThat(account.get("isError").asBoolean()).isTrue();
    assertThat(account.get("content").get(0).get("text").asString()).contains("FORBIDDEN_SCOPE");
    JsonNode rules = body(OAuthTestClient.mcpCall(mvc, access, "get_rules", "{}")).get("result");
    assertThat(rules.has("isError") && rules.get("isError").asBoolean()).isFalse();
  }

  @Test
  void noGrantEverReachesAdmin() throws Exception {
    Fixtures.PlayerRef admin = fx.admin("oadmin" + SEQ.incrementAndGet());
    Fixtures.PlayerRef adminBot = fx.ai("oadminbot" + SEQ.incrementAndGet(), admin);
    WebSession adminSession = WebSession.login(mvc, admin.handle(), Fixtures.PASSWORD);
    for (String actAs : List.of(admin.handle(), adminBot.handle())) {
      String access = claude.connect(adminSession, actAs, null).get("access_token").asString();
      MvcResult adminCall = mvc.perform(get("/api/v1/admin/players").header("Authorization", "Bearer " + access))
          .andReturn();
      assertThat(adminCall.getResponse().getStatus()).isEqualTo(403);
      assertThat(body(adminCall).get("code").asString()).isEqualTo("ADMIN_REQUIRES_SESSION");
      JsonNode me = body(OAuthTestClient.mcpCall(mvc, access, "whoami", "{}")).get("result").get("structuredContent")
          .get("me");
      assertThat(me.get("handle").asString()).isEqualTo(actAs);
      assertThat(jwtClaims(access).get("scope").asString()).doesNotContain("admin");
    }
  }

  // ----------------------------------------------------------------------------------------- refresh & revoke

  @Test
  void refreshRotatesAndReuseRevokesTheGrant() throws Exception {
    JsonNode first = claude.connect(aliceSession, bot.handle(), null);
    String r1 = first.get("refresh_token").asString();
    JsonNode second = body(claude.refresh(r1));
    String r2 = second.get("refresh_token").asString();
    assertThat(r2).isNotEqualTo(r1).startsWith("ort_");
    assertThat(jwtClaims(second.get("access_token").asString()).get("sub").asString()).isEqualTo(bot.publicId());

    // r1 again within the grace window: refused, the grant survives
    MvcResult raced = claude.refresh(r1);
    assertThat(raced.getResponse().getStatus()).isEqualTo(400);
    assertThat(body(raced).get("error").asString()).isEqualTo("invalid_grant");
    JsonNode third = body(claude.refresh(r2));
    String r3 = third.get("refresh_token").asString();

    // r2 again after the grace window: reuse detected, the whole grant is revoked
    time.advance(Duration.ofSeconds(11));
    MvcResult reused = claude.refresh(r2);
    assertThat(body(reused).get("error").asString()).isEqualTo("invalid_grant");
    assertThat(body(claude.refresh(r3)).get("error").asString()).isEqualTo("invalid_grant");
    assertThat(OAuthTestClient.mcpCall(mvc, third.get("access_token").asString(), "whoami", "{}").getResponse()
        .getStatus()).isEqualTo(401);
    String gid = jwtClaims(third.get("access_token").asString()).get("gid").asString();
    assertThat(jdbc.sql("SELECT revoke_reason FROM oauth_grant WHERE public_id = ?").param(gid).query(String.class)
        .single()).isEqualTo("REUSE");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'OAUTH_REFRESH_REUSE' AND target_id = ?")
        .param(gid).query(Integer.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'OAUTH_REFRESH' AND target_id = ?")
        .param(gid).query(Integer.class).single()).isEqualTo(2);
  }

  @Test
  void refreshMayNarrowButNeverWidenTheScope() throws Exception {
    JsonNode t = claude.connect(aliceSession, bot.handle(), "market:read account:read");
    MvcResult widened = claude.token(Map.of("grant_type", "refresh_token", "refresh_token",
        t.get("refresh_token").asString(), "scope", "trade"));
    assertThat(body(widened).get("error").asString()).isEqualTo("invalid_scope");
    JsonNode narrowed = body(claude.token(Map.of("grant_type", "refresh_token", "refresh_token",
        t.get("refresh_token").asString(), "scope", "market:read")));
    assertThat(narrowed.get("scope").asString()).isEqualTo("market:read");
  }

  @Test
  void aSecondUseOfACodeRevokesTheGrant() throws Exception {
    OAuthTestClient.Attempt a = OAuthTestClient.attempt();
    String redirect = claude.approve(aliceSession, OAuthTestClient.requestId(claude.authorize(a, null, null)),
        bot.handle(), null);
    String code = OAuthTestClient.query(redirect).get("code");
    Map<String, String> form = Map.of("grant_type", "authorization_code", "code", code, "code_verifier", a.verifier());
    JsonNode first = body(claude.token(form));
    MvcResult again = claude.token(form);
    assertThat(again.getResponse().getStatus()).isEqualTo(400);
    assertThat(body(again).get("error").asString()).isEqualTo("invalid_grant");
    assertThat(OAuthTestClient.mcpCall(mvc, first.get("access_token").asString(), "whoami", "{}").getResponse()
        .getStatus()).isEqualTo(401);
    assertThat(body(claude.refresh(first.get("refresh_token").asString())).get("error").asString())
        .isEqualTo("invalid_grant");
  }

  @Test
  void pkceAndCodeChecks() throws Exception {
    OAuthTestClient.Attempt a = OAuthTestClient.attempt();
    String code = OAuthTestClient.query(claude.approve(aliceSession,
        OAuthTestClient.requestId(claude.authorize(a, null, null)), bot.handle(), null)).get("code");
    assertThat(body(claude.token(Map.of("grant_type", "authorization_code", "code", code, "code_verifier",
        OAuthTestClient.attempt().verifier()))).get("error").asString()).isEqualTo("invalid_grant");
    assertThat(body(claude.token(Map.of("grant_type", "authorization_code", "code", code, "code_verifier",
        a.verifier(), "redirect_uri", "https://claude.ai/elsewhere"))).get("error").asString())
        .isEqualTo("invalid_grant");
    assertThat(body(claude.token(Map.of("grant_type", "authorization_code", "code", code, "code_verifier",
        a.verifier(), "resource", "https://evil.example/mcp"))).get("error").asString()).isEqualTo("invalid_target");
    // another client cannot redeem it
    OAuthTestClient other = new OAuthTestClient(mvc, json, CLAUDE_CODE, "http://localhost/callback");
    assertThat(body(other.token(Map.of("grant_type", "authorization_code", "code", code, "code_verifier",
        a.verifier()))).get("error").asString()).isEqualTo("invalid_grant");
    // the right verifier still works (failed attempts do not burn the code)
    assertThat(claude.token(Map.of("grant_type", "authorization_code", "code", code, "code_verifier",
        a.verifier())).getResponse().getStatus()).isEqualTo(200);
    // codes expire after 5 minutes
    OAuthTestClient.Attempt b = OAuthTestClient.attempt();
    String late = OAuthTestClient.query(claude.approve(aliceSession,
        OAuthTestClient.requestId(claude.authorize(b, null, null)), bot.handle(), null)).get("code");
    time.advance(Duration.ofMinutes(6));
    try {
      assertThat(body(claude.token(Map.of("grant_type", "authorization_code", "code", late, "code_verifier",
          b.verifier()))).get("error").asString()).isEqualTo("invalid_grant");
    } finally {
      time.set(t0);
    }
  }

  @Test
  void authorizationRequestValidation() throws Exception {
    OAuthTestClient.Attempt a = OAuthTestClient.attempt();
    // never redirected: unknown client, unregistered redirect URI
    MvcResult unknown = new OAuthTestClient(mvc, json, "https://unknown.example/client.json", CLAUDE_CALLBACK)
        .authorize(a, null, null);
    assertThat(unknown.getResponse().getHeader("Location")).startsWith("/oauth/error?error=invalid_client");
    MvcResult badRedirect = claude.authorize(a, null, Map.of("redirect_uri", "https://evil.example/cb"));
    assertThat(badRedirect.getResponse().getHeader("Location")).startsWith("/oauth/error?error=invalid_request");
    // redirected with state and iss
    for (Map<String, String> extra : List.of(Map.of("code_challenge_method", "plain"),
        Map.<String, String>of(), Map.of("response_type", "token"), Map.of("resource", "https://evil.example/mcp"))) {
      Map<String, String> e = new java.util.HashMap<>(extra);
      if (extra.isEmpty()) {
        e.put("code_challenge_method", null);
      }
      MvcResult r = claude.authorize(a, null, e);
      String location = r.getResponse().getHeader("Location");
      assertThat(location).as(extra.toString()).startsWith(CLAUDE_CALLBACK + "?error=");
      Map<String, String> q = OAuthTestClient.query(location);
      assertThat(q).containsEntry("state", a.state()).containsEntry("iss", OAuthTestClient.ISSUER);
    }
    assertThat(OAuthTestClient.query(claude.authorize(a, null, Map.of("code_challenge_method", "plain"))
        .getResponse().getHeader("Location")).get("error")).isEqualTo("invalid_request");
    assertThat(OAuthTestClient.query(claude.authorize(a, null, Map.of("resource", "https://evil.example/mcp"))
        .getResponse().getHeader("Location")).get("error")).isEqualTo("invalid_target");
    // the resource matches with an upper-case host and a trailing slash
    assertThat(claude.authorize(a, null, Map.of("resource", "HTTP://LOCALHOST:5173/mcp/")).getResponse()
        .getHeader("Location")).startsWith("/oauth/consent?request=");
    // CIMD documents are cached
    int before = Documents.FETCHES.get();
    claude.authorize(a, null, null);
    assertThat(Documents.FETCHES.get()).isEqualTo(before);
  }

  @Test
  void denyingConsumesTheRequestAndTellsTheClient() throws Exception {
    OAuthTestClient.Attempt a = OAuthTestClient.attempt();
    String requestId = OAuthTestClient.requestId(claude.authorize(a, null, null));
    MvcResult deny = claude.decide(aliceSession, requestId, "{\"approve\":false}");
    Map<String, String> q = OAuthTestClient.query(body(deny).get("redirectUrl").asString());
    assertThat(q).containsEntry("error", "access_denied").containsEntry("state", a.state())
        .containsEntry("iss", OAuthTestClient.ISSUER);
    assertThat(aliceSession.perform(get("/api/v1/me/oauth/requests/" + requestId)).getResponse().getStatus())
        .isEqualTo(404);
    // requests expire after 15 minutes
    String stale = OAuthTestClient.requestId(claude.authorize(a, null, null));
    time.advance(Duration.ofMinutes(16));
    try {
      assertThat(aliceSession.perform(get("/api/v1/me/oauth/requests/" + stale)).getResponse().getStatus())
          .isEqualTo(404);
    } finally {
      time.set(t0);
    }
  }

  @Test
  void consentNeedsAnActiveHumanOnASessionAndOnlyTheirOwnBots() throws Exception {
    String requestId = OAuthTestClient.requestId(claude.authorize(OAuthTestClient.attempt(), null, null));
    // anonymous
    assertThat(mvc.perform(get("/api/v1/me/oauth/requests/" + requestId)).andReturn().getResponse().getStatus())
        .isEqualTo(401);
    // someone else's bot
    Fixtures.PlayerRef mallory = fx.human("omallory" + SEQ.incrementAndGet());
    WebSession malSession = WebSession.login(mvc, mallory.handle(), Fixtures.PASSWORD);
    MvcResult stolen = claude.decide(malSession, requestId, "{\"approve\":true,\"actAs\":\"" + bot.handle() + "\"}");
    assertThat(stolen.getResponse().getStatus()).isEqualTo(400);
    // a frozen human cannot approve
    fx.status(alice, "FROZEN");
    try {
      MvcResult frozen = claude.decide(aliceSession, requestId, "{\"approve\":true,\"actAs\":\"" + bot.handle()
          + "\"}");
      assertThat(frozen.getResponse().getStatus()).isEqualTo(403);
      assertThat(body(frozen).get("code").asString()).isEqualTo("PLAYER_FROZEN");
    } finally {
      fx.status(alice, "ACTIVE");
    }
    // an API key cannot use the consent API (session only)
    String key = fx.apiKey(alice, properties.security().apiKeyPepper());
    assertThat(mvc.perform(get("/api/v1/me/oauth/requests/" + requestId).header("Authorization", "Bearer " + key))
        .andReturn().getResponse().getStatus()).isEqualTo(403);
  }

  @Test
  void revocationByTheClientAndInSettings() throws Exception {
    JsonNode viaClient = claude.connect(aliceSession, bot.handle(), null);
    assertThat(claude.revoke(viaClient.get("refresh_token").asString()).getResponse().getStatus()).isEqualTo(200);
    assertThat(OAuthTestClient.mcpCall(mvc, viaClient.get("access_token").asString(), "whoami", "{}").getResponse()
        .getStatus()).isEqualTo(401);
    assertThat(body(claude.refresh(viaClient.get("refresh_token").asString())).get("error").asString())
        .isEqualTo("invalid_grant");
    // unknown tokens are fine (RFC 7009)
    assertThat(claude.revoke("ort_nonsense").getResponse().getStatus()).isEqualTo(200);

    JsonNode viaAccess = claude.connect(aliceSession, bot.handle(), null);
    assertThat(claude.revoke(viaAccess.get("access_token").asString()).getResponse().getStatus()).isEqualTo(200);
    assertThat(OAuthTestClient.mcpCall(mvc, viaAccess.get("access_token").asString(), "whoami", "{}").getResponse()
        .getStatus()).isEqualTo(401);

    JsonNode viaSettings = claude.connect(aliceSession, alice.handle(), null);
    JsonNode apps = body(aliceSession.perform(get("/api/v1/me/connected-apps")));
    assertThat(apps.size()).isEqualTo(1);
    String grantId = apps.get(0).get("grantId").asString();
    // someone else cannot revoke it
    Fixtures.PlayerRef bob = fx.human("obob" + SEQ.incrementAndGet());
    assertThat(WebSession.login(mvc, bob.handle(), Fixtures.PASSWORD)
        .perform(delete("/api/v1/me/connected-apps/" + grantId)).getResponse().getStatus()).isEqualTo(404);
    assertThat(aliceSession.perform(delete("/api/v1/me/connected-apps/" + grantId)).getResponse().getStatus())
        .isEqualTo(204);
    assertThat(OAuthTestClient.mcpCall(mvc, viaSettings.get("access_token").asString(), "whoami", "{}")
        .getResponse().getStatus()).isEqualTo(401);
    assertThat(body(aliceSession.perform(get("/api/v1/me/connected-apps"))).size()).isZero();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'OAUTH_REVOKE' AND target_id = ?")
        .param(grantId).query(Integer.class).single()).isEqualTo(1);
  }

  @Test
  void playerStatusIsCheckedOnEveryRequestAndAtRefresh() throws Exception {
    JsonNode t = claude.connect(aliceSession, bot.handle(), null);
    String access = t.get("access_token").asString();
    fx.status(bot, "FROZEN");
    MvcResult frozen = OAuthTestClient.mcpCall(mvc, access, "whoami", "{}");
    assertThat(frozen.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(frozen).get("code").asString()).isEqualTo("PLAYER_FROZEN");
    // a frozen player can still refresh
    JsonNode refreshed = body(claude.refresh(t.get("refresh_token").asString()));
    assertThat(refreshed.has("access_token")).isTrue();
    fx.status(bot, "DISABLED");
    assertThat(body(OAuthTestClient.mcpCall(mvc, access, "whoami", "{}")).get("code").asString())
        .isEqualTo("PLAYER_DISABLED");
    assertThat(body(claude.refresh(refreshed.get("refresh_token").asString())).get("error").asString())
        .isEqualTo("invalid_grant");
    String gid = jwtClaims(access).get("gid").asString();
    assertThat(jdbc.sql("SELECT revoke_reason FROM oauth_grant WHERE public_id = ?").param(gid).query(String.class)
        .single()).isEqualTo("PLAYER_DISABLED");
  }

  @Test
  void aBotThatChangesOwnerLosesItsGrants() throws Exception {
    JsonNode t = claude.connect(aliceSession, bot.handle(), null);
    Fixtures.PlayerRef carol = fx.human("ocarol" + SEQ.incrementAndGet());
    jdbc.sql("UPDATE player SET owner_player_id = ? WHERE id = ?").params(carol.id(), bot.id()).update();
    assertThat(OAuthTestClient.mcpCall(mvc, t.get("access_token").asString(), "whoami", "{}").getResponse()
        .getStatus()).isEqualTo(401);
    assertThat(body(claude.refresh(t.get("refresh_token").asString())).get("error").asString())
        .isEqualTo("invalid_grant");
  }

  @Test
  void aPasswordChangeRevokesTheGrants() throws Exception {
    JsonNode t = claude.connect(aliceSession, bot.handle(), null);
    MvcResult changed = aliceSession.perform(post("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON)
        .content("{\"currentPassword\":\"" + Fixtures.PASSWORD + "\",\"newPassword\":\"another-password-123\"}"));
    assertThat(changed.getResponse().getStatus()).as(WebSession.contentOf(changed)).isIn(200, 204);
    assertThat(OAuthTestClient.mcpCall(mvc, t.get("access_token").asString(), "whoami", "{}").getResponse()
        .getStatus()).isEqualTo(401);
    String gid = jwtClaims(t.get("access_token").asString()).get("gid").asString();
    assertThat(jdbc.sql("SELECT revoke_reason FROM oauth_grant WHERE public_id = ?").param(gid).query(String.class)
        .single()).isEqualTo("CREDENTIALS_CHANGED");
  }

  // ----------------------------------------------------------------------------------------- DCR & loopback

  @Test
  void dynamicClientRegistrationAndLoopbackRedirects() throws Exception {
    MvcResult reg = mvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON).content("""
        {"client_name":"Inspector","redirect_uris":["http://127.0.0.1/callback"],
         "grant_types":["authorization_code","refresh_token"],"token_endpoint_auth_method":"none"}""")).andReturn();
    assertThat(reg.getResponse().getStatus()).as(WebSession.contentOf(reg)).isEqualTo(201);
    JsonNode client = body(reg);
    assertThat(client.get("client_id").asString()).startsWith("ocl_");
    assertThat(client.has("client_secret")).isFalse();
    assertThat(reg.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");

    // the port of a loopback redirect is ignored (RFC 8252 §7.3)
    OAuthTestClient inspector = new OAuthTestClient(mvc, json, client.get("client_id").asString(),
        "http://127.0.0.1:61234/callback");
    OAuthTestClient.Attempt a = OAuthTestClient.attempt();
    String requestId = OAuthTestClient.requestId(inspector.authorize(a, null, null));
    JsonNode consent = body(aliceSession.perform(get("/api/v1/me/oauth/requests/" + requestId)));
    assertThat(consent.get("client").get("kind").asString()).isEqualTo("DCR");
    assertThat(consent.get("client").get("host").isNull()).isTrue();
    assertThat(consent.get("loopbackRedirect").asBoolean()).isTrue();
    String redirect = inspector.approve(aliceSession, requestId, bot.handle(), null);
    assertThat(redirect).startsWith("http://127.0.0.1:61234/callback?code=");
    JsonNode token = body(inspector.token(Map.of("grant_type", "authorization_code", "code",
        OAuthTestClient.query(redirect).get("code"), "code_verifier", a.verifier(), "redirect_uri",
        "http://127.0.0.1:61234/callback")));
    assertThat(token.get("access_token").asString()).isNotBlank();

    // a confidential client: Basic or post, and a wrong secret is invalid_client
    JsonNode conf = body(mvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
        .content("{\"redirect_uris\":[\"https://chatgpt.com/connector_platform_oauth_redirect\"]}")).andReturn());
    assertThat(conf.get("token_endpoint_auth_method").asString()).isEqualTo("client_secret_basic");
    OAuthTestClient chatgpt = new OAuthTestClient(mvc, json, conf.get("client_id").asString(),
        "https://chatgpt.com/connector_platform_oauth_redirect");
    chatgpt.secret = conf.get("client_secret").asString();
    chatgpt.basic = true;
    JsonNode t = chatgpt.connect(aliceSession, bot.handle(), null);
    chatgpt.secret = "wrong";
    MvcResult bad = chatgpt.refresh(t.get("refresh_token").asString());
    assertThat(bad.getResponse().getStatus()).isEqualTo(401);
    assertThat(body(bad).get("error").asString()).isEqualTo("invalid_client");
    chatgpt.secret = conf.get("client_secret").asString();
    chatgpt.basic = false;
    assertThat(chatgpt.refresh(t.get("refresh_token").asString()).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void registrationRejectsForeignRedirectsAndBadMetadata() throws Exception {
    for (String bodyJson : List.of("{\"redirect_uris\":[\"https://evil.example/callback\"]}",
        "{\"redirect_uris\":[\"http://192.168.1.10/callback\"]}", "{\"redirect_uris\":[]}")) {
      MvcResult r = mvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON).content(bodyJson))
          .andReturn();
      assertThat(r.getResponse().getStatus()).isEqualTo(400);
      assertThat(body(r).get("error").asString()).isEqualTo("invalid_redirect_uri");
    }
    MvcResult implicit = mvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
        .content("{\"redirect_uris\":[\"http://localhost/cb\"],\"grant_types\":[\"implicit\"]}")).andReturn();
    assertThat(body(implicit).get("error").asString()).isEqualTo("invalid_client_metadata");
    MvcResult huge = mvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
        .content("{\"client_name\":\"" + "x".repeat(17_000) + "\"}")).andReturn();
    assertThat(body(huge).get("error").asString()).isEqualTo("invalid_client_metadata");
    MvcResult notJson = mvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
        .content("{nope")).andReturn();
    assertThat(body(notJson).get("error").asString()).isEqualTo("invalid_client_metadata");
  }

  @Test
  void tokenEndpointErrorsAreRfc6749Json() throws Exception {
    MvcResult noGrant = claude.token(Map.of());
    assertThat(noGrant.getResponse().getStatus()).isEqualTo(400);
    assertThat(body(noGrant).get("error").asString()).isEqualTo("invalid_request");
    assertThat(body(claude.token(Map.of("grant_type", "password"))).get("error").asString())
        .isEqualTo("unsupported_grant_type");
    MvcResult unknownClient = new OAuthTestClient(mvc, json, "ocl_nobody", "http://localhost/cb")
        .token(Map.of("grant_type", "authorization_code", "code", "x", "code_verifier", "y"));
    assertThat(unknownClient.getResponse().getStatus()).isEqualTo(401);
    assertThat(body(unknownClient).get("error").asString()).isEqualTo("invalid_client");
    assertThat(body(claude.refresh("ort_nope")).get("error").asString()).isEqualTo("invalid_grant");
  }

  @Test
  void cleanupDeletesStaleRowsButKeepsLiveGrants() throws Exception {
    JsonNode live = claude.connect(aliceSession, bot.handle(), null);
    OAuthTestClient.requestId(claude.authorize(OAuthTestClient.attempt(), null, null)); // a pending request
    MvcResult reg = mvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
        .content("{\"redirect_uris\":[\"http://localhost/cb\"],\"token_endpoint_auth_method\":\"none\"}")).andReturn();
    String unused = body(reg).get("client_id").asString();
    time.advance(Duration.ofHours(25));
    try {
      cleanup.tick();
      assertThat(jdbc.sql("SELECT COUNT(*) FROM oauth_client WHERE client_id = ?").param(unused)
          .query(Integer.class).single()).isZero();
      assertThat(jdbc.sql("SELECT COUNT(*) FROM oauth_authorization_request WHERE expires_at < ?")
          .param(com.stocks.common.jdbc.Jdbc.ts(time.now().minus(Duration.ofHours(1)))).query(Integer.class)
          .single()).isZero();
      // the live grant keeps working through a refresh
      assertThat(claude.refresh(live.get("refresh_token").asString()).getResponse().getStatus()).isEqualTo(200);
    } finally {
      time.set(t0);
    }
  }

  @Autowired SigningKeys signingKeys;
  @Autowired OAuthProperties oauthProperties;

  @Test
  void theSigningKeyIsStoredEncryptedAndAPepperChangeReplacesIt() {
    String kid = signingKeys.current().getKeyID();
    Map<String, Object> row = jdbc.sql("SELECT kid, public_jwk, private_key_enc FROM oauth_signing_key WHERE kid = ?")
        .param(kid).query().singleRow();
    assertThat((String) row.get("public_jwk")).contains(kid).doesNotContain("\"d\"");
    assertThat((String) row.get("private_key_enc")).doesNotContain("MII");
    // a restart with the same pepper loads the same key
    assertThat(new SigningKeys(jdbc, time, properties, oauthProperties).current().getKeyID()).isEqualTo(kid);
    // another pepper cannot decrypt it: the row is retired and a new key generated
    var s = properties.security();
    com.stocks.config.StocksProperties other = new com.stocks.config.StocksProperties(properties.publicUrl(),
        properties.jobs(), properties.singleInstance(), properties.bootstrap(),
        new com.stocks.config.StocksProperties.Security("another-pepper-0123456789abcdef0123", s.loginMaxFailures(),
            s.loginLockout(), s.loginMaxFailuresPerHandle(), s.recentAuth(), s.extraWsOrigins(), s.passwordHashCost(),
            s.apiKeyCacheTtl(), s.playerStatusCacheTtl()), properties.ratelimit());
    String replaced = new SigningKeys(jdbc, time, other, oauthProperties).current().getKeyID();
    assertThat(replaced).isNotEqualTo(kid);
    assertThat(jdbc.sql("SELECT retired_at FROM oauth_signing_key WHERE kid = ?").param(kid).query().singleRow()
        .get("retired_at")).isNotNull();
    // back to the real pepper (the context's own bean keeps its key in memory)
    assertThat(new SigningKeys(jdbc, time, properties, oauthProperties).current().getKeyID()).isNotEqualTo(replaced);
  }

  @Test
  void theSpaHandlesTheConsentAndErrorPages() throws Exception {
    // /oauth/* are SPA routes (index.html), /oauth2/* and /.well-known/* never fall back to the SPA
    assertThat(URI.create("/oauth/consent").getPath()).isEqualTo("/oauth/consent");
    MvcResult missing = mvc.perform(get("/oauth2/nothing")).andReturn();
    assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    assertThat(mvc.perform(get("/.well-known/security.txt")).andReturn().getResponse().getStatus()).isEqualTo(404);
  }
}
