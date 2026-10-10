package com.stocks.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.web.RequestIds;
import com.stocks.config.StocksProperties;
import com.stocks.config.stubs.ContractStub;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.AccountService;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.WebSession;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * §17 security chains through the real filter stack (MockMvc): session login with CSRF, lockout, logout, the bearer
 * key path (outcome order, cookies ignored, no CSRF), per-route scopes, session-only routes, problem bodies and the
 * §24.3 headers. Admin-chain rules are in {@link AdminSecurityIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SecurityProbes.class)
class SecurityChainsIT {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;
  @Autowired AccountService accountService;

  Fixtures fx;
  String pepper;

  @BeforeEach
  void setUp() {
    fx = new Fixtures(jdbc, time);
    pepper = properties.security().apiKeyPepper();
  }

  static String handle(String base) {
    return base + SEQ.incrementAndGet();
  }

  static String code(MvcResult r) {
    return JsonPath.read(WebSession.contentOf(r), "$.code");
  }

  MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn();
  }

  MvcResult bearer(MockHttpServletRequestBuilder request, String token) throws Exception {
    return call(request.header("Authorization", "Bearer " + token));
  }

  // ------------------------------------------------------------------------------------------- CSRF & headers

  @Test
  void csrfEndpointIssuesTheCookieWithTheSection24Headers() throws Exception {
    MockHttpServletResponse r = call(get("/api/v1/auth/csrf")).getResponse();
    assertThat(r.getStatus()).isEqualTo(204);
    Cookie xsrf = r.getCookie("XSRF-TOKEN");
    assertThat(xsrf).isNotNull();
    assertThat(xsrf.isHttpOnly()).isFalse();
    assertThat(xsrf.getPath()).isEqualTo("/");
    assertThat(r.getHeader("Content-Security-Policy")).isEqualTo(SecurityConfig.CONTENT_SECURITY_POLICY);
    assertThat(r.getHeader("Referrer-Policy")).isEqualTo("same-origin");
    assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(r.getHeader(RequestIds.HEADER)).startsWith("req_");
  }

  @Test
  void loginWithoutTheCsrfHeaderIsRefused() {
    fx.human(handle("nocsrf"));
    WebSession s = WebSession.anonymous(mvc);
    s.perform(get("/api/v1/auth/csrf"));
    MvcResult r = s.performWithoutCsrf(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"handle\":\"x\",\"password\":\"y\"}"));
    assertThat(r.getResponse().getStatus()).isEqualTo(403);
    assertThat(code(r)).isEqualTo("CSRF_INVALID");
    assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
  }

  // ------------------------------------------------------------------------------------------- login

  @Test
  void loginStartsASessionRotatesCsrfAndReturnsMe() {
    Fixtures.PlayerRef alice = fx.human(handle("alice"));
    WebSession s = WebSession.anonymous(mvc);
    s.perform(get("/api/v1/auth/csrf"));
    String before = s.xsrf();
    MvcResult login = s.login(alice.handle(), Fixtures.PASSWORD);
    assertThat(login.getResponse().getStatus()).isEqualTo(200);
    assertThat((String) JsonPath.read(WebSession.contentOf(login), "$.handle")).isEqualTo(alice.handle());
    assertThat((String) JsonPath.read(WebSession.contentOf(login), "$.playerId")).isEqualTo(alice.publicId());
    assertThat((String) JsonPath.read(WebSession.contentOf(login), "$.authMethod")).isEqualTo("SESSION");
    assertThat((java.util.List<String>) JsonPath.read(WebSession.contentOf(login), "$.scopes"))
        .containsExactly("account:read", "league:read", "market:read", "trade");
    Cookie sid = login.getResponse().getCookie(WebSession.SESSION_COOKIE);
    assertThat(sid).isNotNull();
    assertThat(sid.isHttpOnly()).isTrue();
    assertThat(s.xsrf()).isNotNull().isNotEqualTo(before);

    MvcResult me = s.perform(get("/api/v1/me"));
    assertThat(me.getResponse().getStatus()).isEqualTo(200);
    assertThat((String) JsonPath.read(WebSession.contentOf(me), "$.handle")).isEqualTo(alice.handle());
    String accountId = JsonPath.read(WebSession.contentOf(me), "$.accountId");
    if (accountService instanceof ContractStub) {
      assertThat(accountId).as("no account while WS3 is a Day-0 stub").isNull();
    } else {
      assertThat(accountId).as("the login self-heal opened run 1 (§17.3)").startsWith("acc_");
    }
    assertThat((String) JsonPath.read(WebSession.contentOf(me), "$.preferences.theme")).isEqualTo("DARK");
  }

  @Test
  void aSecondLoginGetsANewSessionId() {
    Fixtures.PlayerRef a = fx.human(handle("fixa"));
    Fixtures.PlayerRef b = fx.human(handle("fixb"));
    WebSession s = WebSession.login(mvc, a.handle(), Fixtures.PASSWORD);
    String first = s.sessionId();
    assertThat(s.login(b.handle(), Fixtures.PASSWORD).getResponse().getStatus()).isEqualTo(200);
    assertThat(s.sessionId()).isNotNull().isNotEqualTo(first);
    assertThat((String) JsonPath.read(WebSession.contentOf(s.perform(get("/api/v1/me"))), "$.handle"))
        .isEqualTo(b.handle());
  }

  @Test
  void badCredentialsUnknownHandlesAndBotsAllGetBadCredentials() {
    Fixtures.PlayerRef owner = fx.human(handle("owner"));
    Fixtures.PlayerRef bot = fx.ai(handle("bot"), owner);
    WebSession s = WebSession.anonymous(mvc);
    for (String[] attempt : new String[][] {
        {owner.handle(), "wrong-password"}, {"nobody_here", Fixtures.PASSWORD}, {bot.handle(), Fixtures.PASSWORD},
        {"BAD HANDLE!", Fixtures.PASSWORD}}) {
      MvcResult r = s.login(attempt[0], attempt[1]);
      assertThat(r.getResponse().getStatus()).as(attempt[0]).isEqualTo(401);
      assertThat(code(r)).isEqualTo("BAD_CREDENTIALS");
      // §17.1 / RFC 9110: a 401 raised by a controller carries the challenge too, not only the security layer's
      assertThat(r.getResponse().getHeader("WWW-Authenticate")).as(attempt[0]).isEqualTo("Bearer realm=\"stocks\"");
    }
    assertThat(s.sessionId()).isNull();
  }

  @Test
  void blankLoginFieldsAreAValidationProblem() {
    WebSession s = WebSession.anonymous(mvc);
    MvcResult r = s.login("", "");
    assertThat(r.getResponse().getStatus()).isEqualTo(400);
    assertThat(code(r)).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void tenFailuresLockTheAccountForFifteenMinutes() {
    Fixtures.PlayerRef p = fx.human(handle("locky"));
    WebSession s = WebSession.anonymous(mvc);
    for (int i = 1; i < 10; i++) {
      assertThat(code(s.login(p.handle(), "wrong-password"))).isEqualTo("BAD_CREDENTIALS");
    }
    MvcResult tenth = s.login(p.handle(), "wrong-password");
    assertThat(tenth.getResponse().getStatus()).isEqualTo(429);
    assertThat(code(tenth)).isEqualTo("LOGIN_LOCKED");
    assertThat(tenth.getResponse().getHeader("Retry-After")).isEqualTo("900");

    MvcResult whileLocked = s.login(p.handle(), Fixtures.PASSWORD);
    assertThat(code(whileLocked)).isEqualTo("LOGIN_LOCKED");

    time.advance(Duration.ofMinutes(15).plusSeconds(1));
    assertThat(s.login(p.handle(), Fixtures.PASSWORD).getResponse().getStatus()).isEqualTo(200);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE target_id = ? AND action = 'LOGIN_FAIL'")
        .param(p.handle()).query(Long.class).single()).isEqualTo(11);
  }

  /**
   * Review regression (§17.3): 10 wrong passwords from a stranger's IP used to lock the account itself, so the owner
   * (the admin) could not re-authenticate for @RecentAuth actions, including the freeze kill switch. The lockout is
   * now per (handle, IP) for anonymous login and never applies to re-authentication from a session.
   */
  @Test
  void aStrangersFailedLoginsLockOnlyTheirOwnIpAndNeverTheOwnersReauth() {
    Fixtures.PlayerRef p = fx.human(handle("victim"));
    WebSession owner = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);
    WebSession attacker = WebSession.anonymous(mvc);
    for (int i = 1; i < 10; i++) {
      assertThat(code(loginFrom(attacker, "203.0.113.9", p.handle(), "wrong-password"))).isEqualTo("BAD_CREDENTIALS");
    }
    assertThat(code(loginFrom(attacker, "203.0.113.9", p.handle(), "wrong-password"))).isEqualTo("LOGIN_LOCKED");
    assertThat(code(loginFrom(attacker, "203.0.113.9", p.handle(), Fixtures.PASSWORD))).isEqualTo("LOGIN_LOCKED");

    MvcResult reauth = owner.perform(post("/api/v1/auth/reauth").contentType(MediaType.APPLICATION_JSON)
        .content("{\"password\":\"" + Fixtures.PASSWORD + "\"}"));
    assertThat(reauth.getResponse().getStatus()).as(WebSession.contentOf(reauth)).isEqualTo(204);
    assertThat(WebSession.anonymous(mvc).login(p.handle(), Fixtures.PASSWORD).getResponse().getStatus())
        .as("the owner's own IP is not locked").isEqualTo(200);
  }

  /** Unknown handles lock exactly like real ones, so the lockout does not reveal which handles exist. */
  @Test
  void unknownHandlesLockLikeRealOnes() {
    WebSession s = WebSession.anonymous(mvc);
    String nobody = handle("nobody");
    for (int i = 1; i < 10; i++) {
      assertThat(code(loginFrom(s, "198.51.100.7", nobody, "wrong-password"))).isEqualTo("BAD_CREDENTIALS");
    }
    MvcResult tenth = loginFrom(s, "198.51.100.7", nobody, "wrong-password");
    assertThat(tenth.getResponse().getStatus()).isEqualTo(429);
    assertThat(code(tenth)).isEqualTo("LOGIN_LOCKED");
  }

  /** The per-handle ceiling (100 failures from anywhere within the window) still bounds distributed guessing. */
  @Test
  void aHandleGuessedFromManyIpsLocksForEveryone() {
    Fixtures.PlayerRef p = fx.human(handle("spray"));
    WebSession s = WebSession.anonymous(mvc);
    int max = properties.security().loginMaxFailuresPerHandle();
    int perIp = properties.security().loginMaxFailures() - 1;   // stay under the per-IP lock
    for (int n = 0, ip = 1; n < max; ip++) {
      for (int i = 0; i < perIp && n < max; i++, n++) {
        loginFrom(s, "192.0.2." + ip, p.handle(), "wrong-password");
      }
    }
    assertThat(code(loginFrom(s, "192.0.2.250", p.handle(), Fixtures.PASSWORD))).isEqualTo("LOGIN_LOCKED");
    time.advance(Duration.ofMinutes(15).plusSeconds(1));
    assertThat(loginFrom(s, "192.0.2.250", p.handle(), Fixtures.PASSWORD).getResponse().getStatus()).isEqualTo(200);
  }

  private static MvcResult loginFrom(WebSession s, String ip, String handle, String password) {
    if (s.xsrf() == null) {
      s.perform(get("/api/v1/auth/csrf"));
    }
    return s.perform(post("/api/v1/auth/login").with(r -> {
      r.setRemoteAddr(ip);
      return r;
    }).contentType(MediaType.APPLICATION_JSON)
        .content("{\"handle\":\"" + handle + "\",\"password\":\"" + password + "\"}"));
  }

  @Test
  void disabledHumansCannotLogInButFrozenOnesCan() {
    Fixtures.PlayerRef disabled = fx.human(handle("gone"));
    Fixtures.PlayerRef frozen = fx.human(handle("ice"));
    fx.status(disabled, "DISABLED");
    fx.status(frozen, "FROZEN");
    MvcResult r = WebSession.anonymous(mvc).login(disabled.handle(), Fixtures.PASSWORD);
    assertThat(r.getResponse().getStatus()).isEqualTo(403);
    assertThat(code(r)).isEqualTo("PLAYER_DISABLED");
    assertThat(WebSession.anonymous(mvc).login(frozen.handle(), Fixtures.PASSWORD).getResponse().getStatus())
        .isEqualTo(200);
  }

  @Test
  void oversizedHeadersAreBoundedAndTheAuditRowIsStillWritten() {
    Fixtures.PlayerRef p = fx.human(handle("bigua"));
    WebSession s = WebSession.anonymous(mvc);
    s.perform(get("/api/v1/auth/csrf"));
    String huge = "x".repeat(10_000);
    MvcResult r = s.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
        .header("User-Agent", huge).header(RequestIds.HEADER, huge)
        .content("{\"handle\":\"" + p.handle() + "\",\"password\":\"" + Fixtures.PASSWORD + "\"}"));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(r.getResponse().getHeader(RequestIds.HEADER)).startsWith("req_");
    var row = jdbc.sql("SELECT user_agent, request_id, channel, ip FROM audit_log WHERE action = 'LOGIN' "
        + "AND target_id = ?").param(p.publicId()).query().singleRow();
    assertThat((String) row.get("user_agent")).hasSize(300);
    assertThat((String) row.get("request_id")).startsWith("req_");
    assertThat(row.get("channel")).isEqualTo("WEB");
    assertThat(row.get("ip")).isEqualTo("127.0.0.1");
  }

  @Test
  void aUserAgentWithControlCharactersIsDroppedNotFatal() {
    Fixtures.PlayerRef p = fx.human(handle("bell"));
    WebSession s = WebSession.anonymous(mvc);
    s.perform(get("/api/v1/auth/csrf"));
    MvcResult r = s.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
        .header("User-Agent", "evil\u0007agent")
        .content("{\"handle\":\"" + p.handle() + "\",\"password\":\"" + Fixtures.PASSWORD + "\"}"));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(jdbc.sql("SELECT user_agent FROM audit_log WHERE action = 'LOGIN' AND target_id = ?")
        .param(p.publicId()).query(String.class).optional()).isEmpty();
  }

  // ------------------------------------------------------------------------------------------- logout

  @Test
  void logoutNeedsCsrfThenEndsTheSession() {
    Fixtures.PlayerRef p = fx.human(handle("bye"));
    WebSession s = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);
    MvcResult noCsrf = s.performWithoutCsrf(post("/api/v1/auth/logout"));
    assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);
    assertThat(code(noCsrf)).isEqualTo("CSRF_INVALID");
    MvcResult out = s.perform(post("/api/v1/auth/logout"));
    assertThat(out.getResponse().getStatus()).isEqualTo(204);
    assertThat(s.sessionId()).isNull();
    MvcResult me = s.perform(get("/api/v1/me"));
    assertThat(me.getResponse().getStatus()).isEqualTo(401);
    assertThat(code(me)).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void anonymousCallersGet401NotCsrfErrors() throws Exception {
    MvcResult me = call(get("/api/v1/me"));
    assertThat(me.getResponse().getStatus()).isEqualTo(401);
    assertThat(me.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"stocks\"");
    MvcResult order = call(post(SecurityProbes.TRADE_PROBE).contentType(MediaType.APPLICATION_JSON).content("{}"));
    assertThat(order.getResponse().getStatus()).isEqualTo(401);
    assertThat(code(order)).isEqualTo("UNAUTHENTICATED");
    assertThat(call(post("/api/v1/auth/logout")).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void sessionWritesNeedCsrf() {
    Fixtures.PlayerRef p = fx.human(handle("writer"));
    WebSession s = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);
    MvcResult without = s.performWithoutCsrf(post(SecurityProbes.TRADE_PROBE));
    assertThat(without.getResponse().getStatus()).isEqualTo(403);
    assertThat(code(without)).isEqualTo("CSRF_INVALID");
    MvcResult with = s.perform(post(SecurityProbes.TRADE_PROBE));
    assertThat(with.getResponse().getStatus()).isEqualTo(200);
  }

  // ------------------------------------------------------------------------------------------- API keys

  @Test
  void aBotKeyAuthenticatesStatelesslyWithoutCsrf() throws Exception {
    Fixtures.PlayerRef owner = fx.human(handle("own"));
    Fixtures.PlayerRef bot = fx.ai(handle("claude"), owner);
    String key = fx.apiKey(bot, pepper);
    MvcResult me = bearer(get("/api/v1/me"), key);
    assertThat(me.getResponse().getStatus()).isEqualTo(200);
    assertThat((String) JsonPath.read(WebSession.contentOf(me), "$.handle")).isEqualTo(bot.handle());
    assertThat((String) JsonPath.read(WebSession.contentOf(me), "$.kind")).isEqualTo("AI");
    assertThat((String) JsonPath.read(WebSession.contentOf(me), "$.authMethod")).isEqualTo("API_KEY");
    assertThat(me.getResponse().getCookie(WebSession.SESSION_COOKIE)).isNull();
    MvcResult order = bearer(post(SecurityProbes.TRADE_PROBE), key); // no X-XSRF-TOKEN
    assertThat(order.getResponse().getStatus()).isEqualTo(200);
    assertThat(order.getResponse().getCookie(WebSession.SESSION_COOKIE)).isNull();
  }

  @Test
  void theKeyWinsOverASessionCookie() throws Exception {
    Fixtures.PlayerRef human = fx.human(handle("cookieman"));
    Fixtures.PlayerRef other = fx.human(handle("keyholder"));
    String key = fx.apiKey(other, Scopes.READ_ONLY, pepper);
    WebSession s = WebSession.login(mvc, human.handle(), Fixtures.PASSWORD);
    Cookie sid = new Cookie(WebSession.SESSION_COOKIE, s.sessionId());
    MvcResult me = bearer(get("/api/v1/me").cookie(sid), key);
    assertThat((String) JsonPath.read(WebSession.contentOf(me), "$.handle")).isEqualTo(other.handle());
    MvcResult badKey = bearer(get("/api/v1/me").cookie(sid), "stk_aaaaaaaaaaaa_" + "b".repeat(43));
    assertThat(badKey.getResponse().getStatus()).isEqualTo(401);
    assertThat(code(badKey)).isEqualTo("INVALID_API_KEY");
    assertThat(badKey.getResponse().getHeader("WWW-Authenticate")).contains("Bearer realm=\"stocks\"");
  }

  @Test
  void keyOutcomesFollowTheSection7Order() throws Exception {
    Fixtures.PlayerRef owner = fx.human(handle("boss"));
    Fixtures.PlayerRef bot = fx.ai(handle("bot"), owner);
    String good = fx.apiKey(bot, pepper);
    String revoked = fx.apiKey(bot, pepper);
    fx.revokeApiKey(revoked);
    String expired = fx.apiKey(bot, Scopes.ALL, pepper, time.now().minusSeconds(1));
    String wrongSecret = good.substring(0, good.length() - 2) + (good.endsWith("AA") ? "BB" : "AA");

    assertThat(code(bearer(get("/api/v1/me"), "stk_short"))).isEqualTo("INVALID_API_KEY");
    assertThat(code(bearer(get("/api/v1/me"), "not-a-key-at-all"))).isEqualTo("INVALID_API_KEY");
    assertThat(code(bearer(get("/api/v1/me"), wrongSecret))).isEqualTo("INVALID_API_KEY");
    assertThat(code(bearer(get("/api/v1/me"), revoked))).isEqualTo("API_KEY_REVOKED");
    assertThat(code(bearer(get("/api/v1/me"), expired))).isEqualTo("API_KEY_EXPIRED");

    fx.status(bot, "FROZEN");
    MvcResult frozen = bearer(get("/api/v1/me"), good);
    assertThat(frozen.getResponse().getStatus()).isEqualTo(403);
    assertThat(code(frozen)).isEqualTo("PLAYER_FROZEN");
    assertThat(code(bearer(get("/api/v1/me"), revoked))).as("revoked beats frozen").isEqualTo("API_KEY_REVOKED");
    fx.status(bot, "DISABLED");
    assertThat(code(bearer(get("/api/v1/me"), good))).isEqualTo("PLAYER_DISABLED");
  }

  @Test
  void scopesAreEnforcedPerRoute() throws Exception {
    Fixtures.PlayerRef human = fx.human(handle("reader"));
    String readOnly = fx.apiKey(human, Scopes.READ_ONLY, pepper);
    String marketOnly = fx.apiKey(human, Set.of(Scopes.MARKET_READ), pepper);

    assertThat(bearer(get("/api/v1/market/__probe"), readOnly).getResponse().getStatus()).isEqualTo(200);
    assertThat(bearer(get(SecurityProbes.ACCOUNT_READ_PROBE), readOnly).getResponse().getStatus()).isEqualTo(200);
    assertThat(bearer(get("/api/v1/players/__probe"), readOnly).getResponse().getStatus()).isEqualTo(200);
    MvcResult trade = bearer(post(SecurityProbes.TRADE_PROBE), readOnly);
    assertThat(trade.getResponse().getStatus()).isEqualTo(403);
    assertThat(code(trade)).isEqualTo("FORBIDDEN_SCOPE");
    assertThat(code(bearer(get(SecurityProbes.ACCOUNT_READ_PROBE), marketOnly))).isEqualTo("FORBIDDEN_SCOPE");
    assertThat(code(bearer(get("/api/v1/players/__probe"), marketOnly))).isEqualTo("FORBIDDEN_SCOPE");
    assertThat(bearer(get("/api/v1/me"), marketOnly).getResponse().getStatus()).as("GET /me: any").isEqualTo(200);
  }

  @Test
  void sessionOnlyRoutesRefuseKeys() throws Exception {
    Fixtures.PlayerRef human = fx.human(handle("sessiononly"));
    String key = fx.apiKey(human, pepper);
    MvcResult r = bearer(get("/api/v1/me/__probe"), key);
    assertThat(r.getResponse().getStatus()).isEqualTo(403);
    assertThat(code(r)).isEqualTo("FORBIDDEN");
    assertThat(code(bearer(post("/api/v1/auth/logout"), key))).isEqualTo("FORBIDDEN");
    WebSession s = WebSession.login(mvc, human.handle(), Fixtures.PASSWORD);
    assertThat(s.perform(get("/api/v1/me/__probe")).getResponse().getStatus()).isEqualTo(200);
  }

  // ------------------------------------------------------------------------------------------- path map

  @Test
  void mcpAndApiDocsNeedCredentials() throws Exception {
    MvcResult mcp = call(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"));
    assertThat(mcp.getResponse().getStatus()).isEqualTo(401);
    assertThat(mcp.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"stocks\"");
    assertThat(call(get("/v3/api-docs")).getResponse().getStatus()).isEqualTo(401);
    // springdoc also serves ${springdoc.api-docs.path}.yaml: it must not fall through to the public web chain
    MvcResult yaml = call(get("/v3/api-docs.yaml"));
    assertThat(yaml.getResponse().getStatus()).isEqualTo(401);
    assertThat(yaml.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"stocks\"");
    assertThat(code(yaml)).isEqualTo("UNAUTHENTICATED");
    assertThat(call(get("/v3/api-docs/swagger-config")).getResponse().getStatus()).isEqualTo(401);
    assertThat(call(get("/api/docs")).getResponse().getStatus()).isEqualTo(401);
    assertThat(call(get("/api/swagger-ui/index.html")).getResponse().getStatus()).isEqualTo(401);

    Fixtures.PlayerRef p = fx.human(handle("docs"));
    WebSession s = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);
    MvcResult docs = s.perform(get("/api/docs"));
    assertThat(docs.getResponse().getStatus()).isEqualTo(302);
    assertThat(docs.getResponse().getHeader("Location")).isEqualTo("/api/swagger-ui/index.html");
    assertThat(s.perform(get("/v3/api-docs")).getResponse().getStatus()).isEqualTo(200);
    MvcResult yamlDocs = s.perform(get("/v3/api-docs.yaml"));
    assertThat(yamlDocs.getResponse().getStatus()).isEqualTo(200);
    assertThat(WebSession.contentOf(yamlDocs)).startsWith("openapi:");

    String key = fx.apiKey(fx.ai(handle("mcpbot"), p), pepper);
    MvcResult authed = bearer(post("/mcp").contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"), key);
    assertThat(authed.getResponse().getStatus()).isNotIn(401, 403);
  }

  @Test
  void unknownApiPathsAre401ThenNotFoundProblems() throws Exception {
    assertThat(call(get("/api/v1/nothing-here")).getResponse().getStatus()).isEqualTo(401);
    Fixtures.PlayerRef p = fx.human(handle("lost"));
    MvcResult r = bearer(get("/api/v1/nothing-here"), fx.apiKey(p, pepper));
    assertThat(r.getResponse().getStatus()).isEqualTo(404);
    assertThat(code(r)).isEqualTo("NOT_FOUND");
  }
}
