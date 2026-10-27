package com.stocks.agents.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.TestPrincipals;
import com.stocks.testkit.WebSession;
import java.time.Instant;
import java.util.List;
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
 * Claude plan usage snapshots through the real chains: the trader sends them with a bot's key, the bot's owner reads
 * them back on a session; strangers, sessions posting and keys reading are refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class PlanUsageWebIT {

  static final AtomicInteger SEQ = new AtomicInteger();
  static final Instant T0 = Instant.parse("2026-10-09T14:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;

  Fixtures fx;
  PlayerRef owner;
  PlayerRef bot;
  PlayerRef stranger;
  String botKey;

  @BeforeEach
  void setUp() {
    if (time.now().isBefore(T0)) {
      time.set(T0);
    }
    fx = new Fixtures(jdbc, time);
    int n = SEQ.incrementAndGet();
    owner = fx.human("puowner" + n);
    bot = fx.ai("pubot" + n, owner);
    stranger = fx.human("pustranger" + n);
    botKey = fx.apiKey(bot, properties.security().apiKeyPepper());
  }

  MockHttpServletResponse bearer(String token, MockHttpServletRequestBuilder r) throws Exception {
    return mvc.perform(r.header("Authorization", "Bearer " + token)).andReturn().getResponse();
  }

  MockHttpServletResponse as(PlayerRef p, MockHttpServletRequestBuilder r) throws Exception {
    jakarta.servlet.http.Cookie token = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse()
        .getCookie(WebSession.XSRF_COOKIE);
    return mvc.perform(r.with(TestPrincipals.as(p)).cookie(token).header(WebSession.XSRF_HEADER, token.getValue()))
        .andReturn().getResponse();
  }

  MockHttpServletResponse report(String body) throws Exception {
    return bearer(botKey, post("/api/v1/agent-runs/plan-usage").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  static String snapshot(String kind, String source, Instant at, String session, String weekly, String extra) {
    return "{\"kind\":\"" + kind + "\",\"source\":\"" + source + "\",\"takenAt\":\"" + at + "\",\"roundRef\":\"round-"
        + at.getEpochSecond() + "\",\"sessionPercent\":" + session + ",\"weeklyPercent\":" + weekly
        + ",\"sessionResetsAt\":\"2026-10-09T18:30:00Z\",\"weeklyResetsAt\":\"2026-10-12T02:00:00Z\"" + extra + "}";
  }

  @Test
  void theTraderReportsAndTheOwnerReadsThemBack() throws Exception {
    Instant now = time.now();
    MockHttpServletResponse before = report(snapshot("ROUND_BEFORE", "USAGE", now.minusSeconds(120), "10", "80.04", ""));
    assertThat(before.getStatus()).as(before.getContentAsString()).isEqualTo(201);
    MockHttpServletResponse after = report(snapshot("CHECKIN_AFTER", "CHECKIN", now.minusSeconds(60), "12.25", "81",
        ",\"runRef\":\"run-0001\",\"tokens\":{\"input\":8,\"cacheWrite\":36000,\"cacheRead\":101000,\"output\":900},"
            + "\"costUsd\":0.17654"));
    assertThat(after.getStatus()).as(after.getContentAsString()).isEqualTo(201);

    MockHttpServletResponse list = as(owner, get("/api/v1/me/bots/plan-usage"));
    assertThat(list.getStatus()).as(list.getContentAsString()).isEqualTo(200);
    String json = list.getContentAsString();
    List<String> kinds = JsonPath.read(json, "$[*].kind");
    assertThat(kinds).containsExactly("CHECKIN_AFTER", "ROUND_BEFORE");   // newest first
    assertThat((String) JsonPath.read(json, "$[0].botHandle")).isEqualTo(bot.handle());
    assertThat(JsonPath.read(json, "$[0].sessionPercent").toString()).isEqualTo("12.3");   // one decimal kept
    assertThat(JsonPath.read(json, "$[1].weeklyPercent").toString()).isEqualTo("80.0");
    assertThat(JsonPath.read(json, "$[0].tokens.cacheWrite").toString()).isEqualTo("36000");
    assertThat(JsonPath.read(json, "$[0].costUsd").toString()).isEqualTo("0.1765");
    assertThat((Object) JsonPath.read(json, "$[1].tokens")).isNull();

    // a stranger owns no bots; a key cannot read the panel; a session cannot report
    List<Object> none = JsonPath.read(as(stranger, get("/api/v1/me/bots/plan-usage")).getContentAsString(), "$");
    assertThat(none).isEmpty();
    assertThat(bearer(botKey, get("/api/v1/me/bots/plan-usage")).getStatus()).isIn(401, 403);
    assertThat(as(owner, post("/api/v1/agent-runs/plan-usage").contentType(MediaType.APPLICATION_JSON)
        .content(snapshot("ROUND_AFTER", "USAGE", now, "1", "1", ""))).getStatus()).isEqualTo(403);
  }

  @Test
  void badSnapshotsAreRefused() throws Exception {
    Instant now = time.now();
    assertThat(field(report(snapshot("ROUND_AFTER", "USAGE", now, "101", "5", "")))).isEqualTo("sessionPercent");
    assertThat(field(report(snapshot("ROUND_AFTER", "USAGE", now, "null", "null", "")))).isEqualTo("weeklyPercent");
    assertThat(field(report(snapshot("ROUND_AFTER", "USAGE", now.plusSeconds(3600), "1", "1", ""))))
        .isEqualTo("takenAt");
    assertThat(field(report(snapshot("ROUND_AFTER", "USAGE", now, "1", "1", ",\"runRef\":\"bad ref\""))))
        .isEqualTo("runRef");
    assertThat(as(owner, get("/api/v1/me/bots/plan-usage?days=15")).getStatus()).isEqualTo(400);
  }

  static String field(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(400);
    return JsonPath.read(r.getContentAsString(), "$.fields[0].field");
  }
}
