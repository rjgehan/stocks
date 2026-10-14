package com.stocks.agents.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.security.Scopes;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.TestPrincipals;
import com.stocks.testkit.WebSession;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 2 WS5 endpoints through the real chains: run reports with the bot's own key (§20.6), the public summary and
 * the admin Bots page, the public AI journal and its JOURNAL feed item (§20.7), Recent API calls (§17.10), and the
 * stream's request checks (§19.3; the streaming itself is {@code StreamIT}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class AgentsWebIT {

  static final AtomicInteger SEQ = new AtomicInteger();
  static final Instant T0 = Instant.parse("2026-10-09T14:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;
  @Autowired SettingsService settings;

  Fixtures fx;
  PlayerRef owner;
  PlayerRef bot;
  PlayerRef stranger;
  String botKey;
  String botHandle;

  @BeforeEach
  void setUp() {
    if (time.now().isBefore(T0)) {
      time.set(T0);
    }
    fx = new Fixtures(jdbc, time);
    int n = SEQ.incrementAndGet();
    owner = fx.human("owner" + n);
    botHandle = "bot" + n;
    bot = fx.ai(botHandle, owner);
    stranger = fx.human("stranger" + n);
    fx.asset("NVDA");
    botKey = fx.apiKey(bot, properties.security().apiKeyPepper());
  }

  MockHttpServletResponse bearer(String token, MockHttpServletRequestBuilder r) throws Exception {
    return mvc.perform(r.header("Authorization", "Bearer " + token)).andReturn().getResponse();
  }

  /**
   * As {@code p} on a session, with a real {@code XSRF-TOKEN} cookie and header (spring-security-test's
   * {@code csrf()} would swap the shared CsrfFilter's repository for the rest of the context).
   */
  MockHttpServletResponse as(PlayerRef p, MockHttpServletRequestBuilder r) throws Exception {
    jakarta.servlet.http.Cookie token = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse()
        .getCookie(WebSession.XSRF_COOKIE);
    return mvc.perform(r.with(TestPrincipals.as(p)).cookie(token).header(WebSession.XSRF_HEADER, token.getValue()))
        .andReturn().getResponse();
  }

  static <T> T json(MockHttpServletResponse r, String path) throws Exception {
    return JsonPath.read(r.getContentAsString(), path);
  }

  String report(String runRef, String status) {
    Instant start = time.now().minusSeconds(120);
    return """
        {"runRef":"%s","trigger":"schedule","slot":"09:45","model":"claude-opus-5-5","status":"%s",
         "startedAt":"%s","endedAt":"%s","turns":9,"toolCalls":14,"toolErrors":1,"orders":2,
         "tokens":{"input":5210,"cacheRead":96000,"cacheWrite":12400,"output":4100},
         "costUsd":"0.1834","daySpentUsd":"0.1834","dailyCapUsd":"1.00","message":"done","runnerVersion":"1.2.0",
         "tools":[{"tool":"get_quotes","code":"OK","calls":5},{"tool":"place_order","code":"RATE_LIMITED","calls":1}]}
        """.formatted(runRef, status, start, start.plusSeconds(94));
  }

  MockHttpServletResponse postReport(String token, String body) throws Exception {
    return bearer(token, post("/api/v1/agent-runs").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void aBotReportsItsRunsWithItsOwnKey() throws Exception {
    MockHttpServletResponse created = postReport(botKey, report("20261009T134500Z-bot-abc123", "completed"));
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    assertThat((String) json(created, "$.runId")).startsWith("arn_");
    assertThat((String) json(created, "$.player.handle")).isEqualTo(botHandle);
    assertThat((String) json(created, "$.status")).isEqualTo("COMPLETED");
    assertThat((String) json(created, "$.costUsd")).isEqualTo("0.183400");        // §6.3 decimal string
    assertThat((Integer) json(created, "$.tools.length()")).isEqualTo(2);

    MockHttpServletResponse again = postReport(botKey, report("20261009T134500Z-bot-abc123", "ERROR"));
    assertThat(again.getStatus()).isEqualTo(200);
    assertThat((String) json(again, "$.status")).isEqualTo("COMPLETED");

    // a human's key (even with trade) and a session may not report
    String humanTrade = fx.apiKey(owner, properties.security().apiKeyPepper());
    MockHttpServletResponse human = postReport(humanTrade, report("ref-human-0001", "completed"));
    assertThat(human.getStatus()).isEqualTo(403);
    assertThat((String) json(human, "$.code")).isEqualTo("FORBIDDEN");
    MockHttpServletResponse session = as(owner, post("/api/v1/agent-runs").contentType(MediaType.APPLICATION_JSON)
        .content(report("ref-session-01", "completed")));
    assertThat(session.getStatus()).isEqualTo(403);
    // a bot key without trade
    String readOnly = fx.apiKey(bot, Set.of(Scopes.MARKET_READ, Scopes.LEAGUE_READ),
        properties.security().apiKeyPepper());
    MockHttpServletResponse scope = postReport(readOnly, report("ref-readonly-1", "completed"));
    assertThat(scope.getStatus()).isEqualTo(403);
    assertThat((String) json(scope, "$.code")).isEqualTo("FORBIDDEN_SCOPE");
  }

  @Test
  void reportsAreBoundedAndValidated() throws Exception {
    String big = report("ref-big-000001", "completed").replace("\"message\":\"done\"",
        "\"message\":\"" + "x".repeat(40_000) + "\"");
    MockHttpServletResponse tooBig = postReport(botKey, big);
    assertThat(tooBig.getStatus()).isEqualTo(400);
    assertThat((String) json(tooBig, "$.code")).isEqualTo("VALIDATION_FAILED");
    MockHttpServletResponse malformed = postReport(botKey, "{not json");
    assertThat(malformed.getStatus()).isEqualTo(400);
    assertThat((String) json(malformed, "$.code")).isEqualTo("VALIDATION_FAILED");
    MockHttpServletResponse badStatus = postReport(botKey, report("ref-badstatus1", "sleeping"));
    assertThat(badStatus.getStatus()).isEqualTo(400);
    MockHttpServletResponse badFields = postReport(botKey, report("short", "completed").replace("\"09:45\"",
        "\"25:00\""));
    assertThat(badFields.getStatus()).isEqualTo(400);
    List<String> fields = json(badFields, "$.fields[*].field");
    assertThat(fields).containsExactlyInAnyOrder("runRef", "slot");
  }

  @Test
  void theProfileSummaryIsPublicAndTheAdminSeesBotsAndRuns() throws Exception {
    postReport(botKey, report("ref-summary-01", "error"));
    MockHttpServletResponse summary = as(stranger, get("/api/v1/players/" + botHandle + "/agent-runs"));
    assertThat(summary.getStatus()).isEqualTo(200);
    assertThat((Integer) json(summary, "$.runs")).isEqualTo(1);
    assertThat((String) json(summary, "$.lastStatus")).isEqualTo("ERROR");
    assertThat(summary.getContentAsString()).doesNotContain("costUsd").doesNotContain("message");
    assertThat(bearer(botKey, get("/api/v1/players/" + botHandle + "/agent-runs")).getStatus()).isEqualTo(200);
    assertThat(as(stranger, get("/api/v1/players/nobody_here/agent-runs")).getStatus()).isEqualTo(404);

    PlayerRef admin = fx.admin("admin" + SEQ.incrementAndGet());
    WebSession root = WebSession.login(mvc, admin.handle(), Fixtures.PASSWORD);
    MockHttpServletResponse bots = root.perform(get("/api/v1/admin/bots")).getResponse();
    assertThat(bots.getStatus()).isEqualTo(200);
    List<Map<String, Object>> mine = JsonPath.read(bots.getContentAsString(),
        "$[?(@.player.handle == '" + botHandle + "')]");
    assertThat(mine).singleElement().satisfies(b -> {
      assertThat(JsonPath.<String>read(b, "$.lastRun.runRef")).isEqualTo("ref-summary-01");
      assertThat(JsonPath.<String>read(b, "$.lastProblem.status")).isEqualTo("ERROR");
      assertThat(JsonPath.<String>read(b, "$.today.capUsd")).isEqualTo("1.000000");
      assertThat(JsonPath.<List<Object>>read(b, "$.days")).hasSize(14);
      assertThat(JsonPath.<List<Object>>read(b, "$.tools")).hasSize(2);
    });
    MockHttpServletResponse runs = root.perform(get("/api/v1/admin/agent-runs").param("handle", botHandle)
        .param("status", "error")).getResponse();
    assertThat(runs.getStatus()).isEqualTo(200);
    assertThat((List<?>) json(runs, "$.items")).hasSize(1);

    assertThat(as(owner, get("/api/v1/admin/bots")).getStatus()).isEqualTo(403);
    MockHttpServletResponse withKey = bearer(botKey, get("/api/v1/admin/bots"));
    assertThat(withKey.getStatus()).isEqualTo(403);
    assertThat((String) json(withKey, "$.code")).isEqualTo("ADMIN_REQUIRES_SESSION");
  }

  @Test
  void journalNotesArePublicAtOnceByDefault() throws Exception {
    MockHttpServletResponse written = bearer(botKey, post("/api/v1/journal").contentType(MediaType.APPLICATION_JSON)
        .content("{\"text\":\"Holding cash into CPI.\",\"symbols\":[\"spy\"]}"));
    assertThat(written.getStatus()).as(written.getContentAsString()).isEqualTo(201);
    MockHttpServletResponse page = as(stranger, get("/api/v1/players/" + botHandle + "/journal"));
    assertThat(page.getStatus()).isEqualTo(200);
    assertThat((List<String>) json(page, "$.items[*].untrusted.text")).containsExactly("Holding cash into CPI.");
  }

  @Test
  void botsWriteJournalNotesThatBecomePublicAfterTheDelay() throws Exception {
    settings.put("social.tradeFeedDelay", "PT15M", owner.id());     // the admin can still set a delay
    try {
      journalNotesFollowTheDelay();
    } finally {
      settings.put("social.tradeFeedDelay", "PT0S", owner.id());
    }
  }

  private void journalNotesFollowTheDelay() throws Exception {
    MockHttpServletResponse written = bearer(botKey, post("/api/v1/journal").contentType(MediaType.APPLICATION_JSON)
        .content("{\"text\":\"Thesis:\\nNVDA guidance <b>raised</b>.\",\"symbols\":[\"nvda\"]}"));
    assertThat(written.getStatus()).as(written.getContentAsString()).isEqualTo(201);
    assertThat((String) json(written, "$.note.id")).startsWith("jnl_");
    assertThat((String) json(written, "$.note.untrusted.text")).isEqualTo("Thesis: NVDA guidance <b>raised</b>.");
    assertThat((Integer) json(written, "$.remainingToday")).isEqualTo(11);

    MockHttpServletResponse human = as(owner, post("/api/v1/journal").contentType(MediaType.APPLICATION_JSON)
        .content("{\"text\":\"hello\"}"));
    assertThat(human.getStatus()).isEqualTo(403);
    assertThat((List<?>) json(as(stranger, get("/api/v1/players/" + botHandle + "/journal")), "$.items")).isEmpty();

    time.set(time.now().plus(Duration.ofMinutes(16)));
    MockHttpServletResponse page = as(stranger, get("/api/v1/players/" + botHandle + "/journal"));
    assertThat(page.getStatus()).isEqualTo(200);
    assertThat((List<String>) json(page, "$.items[*].symbols[0]")).containsExactly("NVDA");

    // the feed shows it as a JOURNAL item (FeedEventListener writes it asynchronously)
    String note = null;
    for (int i = 0; i < 50 && note == null; i++) {
      MockHttpServletResponse feed = as(stranger, get("/api/v1/feed").param("limit", "200"));
      List<String> notes = JsonPath.read(feed.getContentAsString(),
          "$.items[?(@.kind == 'JOURNAL' && @.player.handle == '" + botHandle + "')].payload.untrusted.note");
      note = notes.isEmpty() ? null : notes.get(0);
      if (note == null) {
        Thread.sleep(100);
      }
    }
    assertThat(note).isEqualTo("Thesis: NVDA guidance <b>raised</b>.");
  }

  @Test
  void recentApiCallsShowYourKeysAndYourBotsOnly() throws Exception {
    bearer(botKey, get("/api/v1/players/" + botHandle + "/agent-runs"));
    bearer(botKey, get("/api/v1/players/" + botHandle + "/agent-runs"));
    String marketOnly = fx.apiKey(bot, Set.of(Scopes.MARKET_READ), properties.security().apiKeyPepper());
    bearer(marketOnly, get("/api/v1/account"));                                // 403 FORBIDDEN_SCOPE, recorded

    MockHttpServletResponse mine = as(owner, get("/api/v1/me/api-calls"));
    assertThat(mine.getStatus()).as(mine.getContentAsString()).isEqualTo(200);
    List<Map<String, Object>> rows = json(mine, "$.items");
    assertThat(rows).anySatisfy(r -> {
      assertThat(r.get("endpoint")).isEqualTo("GET /api/v1/players/{handle}/agent-runs");
      assertThat(r.get("calls")).isEqualTo(2);
      assertThat(r.get("ok")).isEqualTo(true);
      assertThat(r.get("channel")).isEqualTo("REST_KEY");
      assertThat(r.get("handle")).isEqualTo(botHandle);
    });
    assertThat(rows).anySatisfy(r -> {
      assertThat(r.get("endpoint")).isEqualTo("GET /api/v1/account");
      assertThat(r.get("outcome")).isEqualTo("403");
      assertThat(r.get("ok")).isEqualTo(false);
    });
    assertThat((List<?>) json(as(owner, get("/api/v1/me/api-calls").param("handle", botHandle)), "$.items"))
        .isNotEmpty();
    assertThat((List<?>) json(as(stranger, get("/api/v1/me/api-calls")), "$.items")).isEmpty();
    assertThat(as(stranger, get("/api/v1/me/api-calls").param("handle", botHandle)).getStatus()).isEqualTo(404);
    assertThat(bearer(botKey, get("/api/v1/me/api-calls")).getStatus()).isEqualTo(403);       // session only

    PlayerRef admin = fx.admin("admin" + SEQ.incrementAndGet());
    WebSession root = WebSession.login(mvc, admin.handle(), Fixtures.PASSWORD);
    MockHttpServletResponse all = root.perform(get("/api/v1/admin/api-calls").param("handle", botHandle))
        .getResponse();
    assertThat(all.getStatus()).isEqualTo(200);
    assertThat((List<?>) json(all, "$.items")).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void theStreamChecksItsCallerTopicsAndSymbols() throws Exception {
    MockHttpServletResponse session = as(owner, get("/api/v1/stream"));
    assertThat(session.getStatus()).isEqualTo(403);
    assertThat((String) json(session, "$.code")).isEqualTo("FORBIDDEN");
    String marketOnly = fx.apiKey(bot, Set.of(Scopes.MARKET_READ), properties.security().apiKeyPepper());
    MockHttpServletResponse scope = bearer(marketOnly, get("/api/v1/stream").param("topics", "quotes,orders")
        .param("symbols", "AAPL"));
    assertThat(scope.getStatus()).isEqualTo(403);
    assertThat((String) json(scope, "$.code")).isEqualTo("FORBIDDEN_SCOPE");
    assertThat((String) json(scope, "$.detail")).contains("account:read");
    assertThat(bearer(botKey, get("/api/v1/stream").param("topics", "trades")).getStatus()).isEqualTo(400);
    assertThat(bearer(botKey, get("/api/v1/stream").param("topics", "quotes")).getStatus()).isEqualTo(400);
    assertThat(bearer(botKey, get("/api/v1/stream").param("symbols", "AAPL,not a symbol")).getStatus())
        .isEqualTo(400);
    String tradeOnly = fx.apiKey(bot, Set.of(Scopes.TRADE), properties.security().apiKeyPepper());
    assertThat((String) json(bearer(tradeOnly, get("/api/v1/stream")), "$.code")).isEqualTo("FORBIDDEN_SCOPE");
  }
}
