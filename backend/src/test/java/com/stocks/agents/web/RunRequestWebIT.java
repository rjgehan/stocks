package com.stocks.agents.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.security.Scopes;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.WebSession;
import java.time.Instant;
import java.util.List;
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
 * §20.9 "Run now" through the real chains: the owner's session routes ({@code /me/**}: session only, CSRF), the bot's
 * claim and finish with its own key ({@code trade}, AI keys only), and who may call which; "Run all" likewise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class RunRequestWebIT {

  static final AtomicInteger SEQ = new AtomicInteger();
  static final Instant T0 = Instant.parse("2026-10-09T14:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;

  Fixtures fx;
  PlayerRef owner;
  PlayerRef stranger;
  String botHandle;
  String botKey;
  String runs;

  @BeforeEach
  void setUp() {
    if (time.now().isBefore(T0)) {
      time.set(T0);
    }
    fx = new Fixtures(jdbc, time);
    int n = SEQ.incrementAndGet();
    owner = fx.human("rr_owner" + n);
    stranger = fx.human("rr_stranger" + n);
    botHandle = "rr_bot" + n;
    PlayerRef bot = fx.ai(botHandle, owner);
    botKey = fx.apiKey(bot, properties.security().apiKeyPepper());
    runs = "/api/v1/me/bots/" + botHandle + "/run-requests";
  }

  MockHttpServletResponse bearer(String token, MockHttpServletRequestBuilder r) throws Exception {
    return mvc.perform(r.header("Authorization", "Bearer " + token)).andReturn().getResponse();
  }

  static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, String body) {
    return r.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  static <T> T read(MockHttpServletResponse r, String path) throws Exception {
    return JsonPath.read(r.getContentAsString(), path);
  }

  @Test
  void theOwnerAsksOnASessionAndTheBotClaimsAndFinishesWithItsKey() throws Exception {
    WebSession session = WebSession.login(mvc, owner.handle(), Fixtures.PASSWORD);
    MockHttpServletResponse created = session.perform(json(post(runs), "{\"dryRun\":true}")).getResponse();
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    String id = read(created, "$.id");
    assertThat(id).startsWith("arq_");
    assertThat((String) read(created, "$.handle")).isEqualTo(botHandle);
    assertThat((String) read(created, "$.requestedBy")).isEqualTo(owner.handle());
    assertThat((Boolean) read(created, "$.dryRun")).isTrue();
    assertThat((String) read(created, "$.status")).isEqualTo("PENDING");
    assertThat(created.getContentAsString()).contains("\"claimedAt\":null");

    MockHttpServletResponse again = session.perform(json(post(runs), "{}")).getResponse();
    assertThat(again.getStatus()).isEqualTo(409);
    assertThat((String) read(again, "$.code")).isEqualTo("RUN_REQUEST_CONFLICT");
    assertThat((String) read(again, "$.detail")).contains("already has a run request waiting for the trader");

    MockHttpServletResponse claimed = bearer(botKey, post("/api/v1/agent-runs/requests/claim"));
    assertThat(claimed.getStatus()).as(claimed.getContentAsString()).isEqualTo(200);
    assertThat((String) read(claimed, "$.id")).isEqualTo(id);
    assertThat((String) read(claimed, "$.status")).isEqualTo("CLAIMED");
    MockHttpServletResponse none = bearer(botKey, post("/api/v1/agent-runs/requests/claim"));
    assertThat(none.getStatus()).isEqualTo(204);
    assertThat(none.getContentAsString()).isEmpty();

    String finish = "/api/v1/agent-runs/requests/" + id + "/finish";
    MockHttpServletResponse bad = bearer(botKey, json(post(finish), "{\"status\":\"sleeping\"}"));
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat((String) read(bad, "$.code")).isEqualTo("VALIDATION_FAILED");
    MockHttpServletResponse done = bearer(botKey, json(post(finish), "{\"status\":\"done\",\"message\":\"ok\"}"));
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
    assertThat((String) read(done, "$.status")).isEqualTo("DONE");
    assertThat((String) read(done, "$.message")).isEqualTo("ok");
    assertThat(bearer(botKey, json(post(finish), "{\"status\":\"DONE\"}")).getStatus()).isEqualTo(200);
    MockHttpServletResponse late = bearer(botKey, json(post(finish), "{\"status\":\"FAILED\"}"));
    assertThat(late.getStatus()).isEqualTo(409);
    assertThat((String) read(late, "$.code")).isEqualTo("RUN_REQUEST_CONFLICT");

    MockHttpServletResponse list = session.perform(get(runs).param("limit", "5")).getResponse();
    assertThat(list.getStatus()).isEqualTo(200);
    assertThat((List<String>) read(list, "$.requests[*].status")).containsExactly("DONE");
    assertThat((String) read(list, "$.botStatus")).isEqualTo("ACTIVE");
    assertThat((String) read(list, "$.traderSeenAt")).isNotNull();   // the claims above
    assertThat((String) read(list, "$.asOf")).isNotNull();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'BOT_RUN_REQUEST' AND target_id = ?")
        .param(botHandle).query(Integer.class).single()).isEqualTo(1);
  }

  @Test
  void whoMayCallWhich() throws Exception {
    // the owner's routes: a browser session with CSRF, the owner or the admin
    WebSession session = WebSession.login(mvc, owner.handle(), Fixtures.PASSWORD);
    MockHttpServletResponse noCsrf = session.performWithoutCsrf(json(post(runs), "{}")).getResponse();
    assertThat(noCsrf.getStatus()).isEqualTo(403);
    assertThat((String) read(noCsrf, "$.code")).isEqualTo("CSRF_INVALID");
    MockHttpServletResponse withKey = bearer(botKey, json(post(runs), "{}"));
    assertThat(withKey.getStatus()).isEqualTo(403);
    assertThat((String) read(withKey, "$.code")).isEqualTo("FORBIDDEN");             // session only
    WebSession other = WebSession.login(mvc, stranger.handle(), Fixtures.PASSWORD);
    MockHttpServletResponse notYours = other.perform(json(post(runs), "{}")).getResponse();
    assertThat(notYours.getStatus()).isEqualTo(403);
    assertThat((String) read(notYours, "$.detail")).contains("owner or the admin");
    assertThat(other.perform(get(runs)).getResponse().getStatus()).isEqualTo(403);
    PlayerRef admin = fx.admin("rr_admin" + SEQ.incrementAndGet());
    WebSession root = WebSession.login(mvc, admin.handle(), Fixtures.PASSWORD);
    MockHttpServletResponse byAdmin = root.perform(json(post(runs), "{}")).getResponse();
    assertThat(byAdmin.getStatus()).as(byAdmin.getContentAsString()).isEqualTo(201);
    assertThat((String) read(byAdmin, "$.requestedBy")).isEqualTo(admin.handle());
    assertThat(session.perform(get("/api/v1/me/bots/nobody_here/run-requests")).getResponse().getStatus())
        .isEqualTo(404);

    // the bot's routes: its own key with trade; never a session or a human's key
    String claim = "/api/v1/agent-runs/requests/claim";
    MockHttpServletResponse bySession = session.perform(post(claim)).getResponse();
    assertThat(bySession.getStatus()).isEqualTo(403);
    assertThat((String) read(bySession, "$.code")).isEqualTo("FORBIDDEN");
    String humanKey = fx.apiKey(owner, properties.security().apiKeyPepper());
    assertThat((String) read(bearer(humanKey, post(claim)), "$.code")).isEqualTo("FORBIDDEN");
    String readOnly = fx.apiKey(fx.ai("rr_ro" + SEQ.incrementAndGet(), owner), Set.of(Scopes.ACCOUNT_READ),
        properties.security().apiKeyPepper());
    assertThat((String) read(bearer(readOnly, post(claim)), "$.code")).isEqualTo("FORBIDDEN_SCOPE");
    MockHttpServletResponse unknown = bearer(botKey, json(post("/api/v1/agent-runs/requests/arq_x/finish"),
        "{\"status\":\"DONE\"}"));
    assertThat(unknown.getStatus()).isEqualTo(404);
    assertThat(bearer(botKey, post(claim)).getStatus()).isEqualTo(200);

    // the trader's heartbeat (busy, or no free session slot): the same callers, 204, claims nothing
    String heartbeat = "/api/v1/agent-runs/requests/heartbeat";
    assertThat((String) read(session.perform(post(heartbeat)).getResponse(), "$.code")).isEqualTo("FORBIDDEN");
    assertThat((String) read(bearer(humanKey, post(heartbeat)), "$.code")).isEqualTo("FORBIDDEN");
    assertThat((String) read(bearer(readOnly, post(heartbeat)), "$.code")).isEqualTo("FORBIDDEN_SCOPE");
    MockHttpServletResponse alive = bearer(botKey, post(heartbeat));
    assertThat(alive.getStatus()).as(alive.getContentAsString()).isEqualTo(204);
    assertThat(alive.getContentAsString()).isEmpty();
  }

  @Test
  void aHeartbeatKeepsTheBotServedWithoutClaiming() throws Exception {
    WebSession session = WebSession.login(mvc, owner.handle(), Fixtures.PASSWORD);
    MockHttpServletResponse before = session.perform(get(runs)).getResponse();
    assertThat((Object) read(before, "$.traderSeenAt")).isNull();
    assertThat(session.perform(json(post(runs), "{}")).getResponse().getStatus()).isEqualTo(201);
    assertThat(bearer(botKey, post("/api/v1/agent-runs/requests/heartbeat")).getStatus()).isEqualTo(204);
    MockHttpServletResponse after = session.perform(get(runs)).getResponse();
    assertThat((String) read(after, "$.traderSeenAt")).isNotNull();
    assertThat((List<String>) read(after, "$.requests[*].status")).containsExactly("PENDING");
  }

  @Test
  void aFrozenOwnerSignsInAndLooksButRunsNothing() throws Exception {
    assertThat(bearer(botKey, post("/api/v1/agent-runs/requests/claim")).getStatus()).isEqualTo(204);
    fx.status(owner, "FROZEN");
    WebSession session = WebSession.login(mvc, owner.handle(), Fixtures.PASSWORD);   // a FROZEN human may sign in
    MockHttpServletResponse one = session.perform(json(post(runs), "{}")).getResponse();
    assertThat(one.getStatus()).as(one.getContentAsString()).isEqualTo(403);
    assertThat((String) read(one, "$.code")).isEqualTo("PLAYER_FROZEN");
    MockHttpServletResponse all = session.perform(json(post("/api/v1/me/bots/run-requests"), "{}")).getResponse();
    assertThat(all.getStatus()).as(all.getContentAsString()).isEqualTo(403);
    assertThat((String) read(all, "$.code")).isEqualTo("PLAYER_FROZEN");
    MockHttpServletResponse list = session.perform(get(runs)).getResponse();
    assertThat(list.getStatus()).isEqualTo(200);
    assertThat((List<Object>) read(list, "$.requests")).isEmpty();
  }

  @Test
  void runAllOnTheOwnersSessionAsksForEveryBotATraderServes() throws Exception {
    String all = "/api/v1/me/bots/run-requests";
    String quiet = "rr_quiet" + SEQ.incrementAndGet();
    fx.ai(quiet, owner);                                           // no trader asks for it
    assertThat(bearer(botKey, post("/api/v1/agent-runs/requests/claim")).getStatus()).isEqualTo(204);

    // session only, with CSRF, like the per-bot route
    WebSession session = WebSession.login(mvc, owner.handle(), Fixtures.PASSWORD);
    MockHttpServletResponse noCsrf = session.performWithoutCsrf(json(post(all), "{}")).getResponse();
    assertThat(noCsrf.getStatus()).isEqualTo(403);
    assertThat((String) read(noCsrf, "$.code")).isEqualTo("CSRF_INVALID");
    MockHttpServletResponse withKey = bearer(botKey, json(post(all), "{}"));
    assertThat(withKey.getStatus()).isEqualTo(403);
    assertThat((String) read(withKey, "$.code")).isEqualTo("FORBIDDEN");

    MockHttpServletResponse ran = session.perform(json(post(all), "{\"dryRun\":true}")).getResponse();
    assertThat(ran.getStatus()).as(ran.getContentAsString()).isEqualTo(200);
    assertThat((Boolean) read(ran, "$.dryRun")).isTrue();
    assertThat((Integer) read(ran, "$.requested")).isEqualTo(1);
    assertThat((List<String>) read(ran, "$.results[*].handle")).containsExactly(botHandle, quiet);
    assertThat((String) read(ran, "$.results[0].outcome")).isEqualTo("REQUESTED");
    assertThat((String) read(ran, "$.results[0].request.status")).isEqualTo("PENDING");
    assertThat((Boolean) read(ran, "$.results[0].request.dryRun")).isTrue();
    assertThat((String) read(ran, "$.results[1].outcome")).isEqualTo("SKIPPED");
    assertThat((String) read(ran, "$.results[1].reason")).isEqualTo("NO_TRADER");
    assertThat((String) read(ran, "$.results[1].request")).isNull();

    // the per-bot list shows it, and a second Run all finds it on its way
    MockHttpServletResponse list = session.perform(get(runs)).getResponse();
    assertThat((List<String>) read(list, "$.requests[*].id")).containsExactly(
        (String) read(ran, "$.results[0].request.id"));
    MockHttpServletResponse again = session.perform(json(post(all), "{}")).getResponse();
    assertThat((String) read(again, "$.results[0].outcome")).isEqualTo("ALREADY_OPEN");
    assertThat((Integer) read(again, "$.requested")).isZero();
    // a player without bots runs none
    WebSession other = WebSession.login(mvc, stranger.handle(), Fixtures.PASSWORD);
    MockHttpServletResponse none = other.perform(post(all)).getResponse();
    assertThat(none.getStatus()).as(none.getContentAsString()).isEqualTo(200);
    assertThat((List<Object>) read(none, "$.results")).isEmpty();
  }
}
