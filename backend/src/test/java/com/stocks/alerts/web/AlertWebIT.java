package com.stocks.alerts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.security.Scopes;
import com.stocks.portfolio.ScriptedMarketConfig;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestClockConfig;
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
 * §18.2 {@code /alerts/**} through the real chains (§17.1): reading with {@code account:read}, writing with
 * {@code trade} (bots too), status codes (201 new, 200 identical, 204 delete), problem codes and per-player ownership.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, ScriptedMarketConfig.class})
class AlertWebIT {

  static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;

  PlayerRef alice;
  PlayerRef bob;
  String aliceTrade;
  String aliceRead;
  String bobTrade;
  String botKey;

  @BeforeEach
  void setUp() {
    Fixtures fx = new Fixtures(jdbc, time);
    int n = SEQ.incrementAndGet();
    alice = fx.human("al_alice" + n);
    bob = fx.human("al_bob" + n);
    PlayerRef bot = fx.ai("al_bot" + n, alice);
    String pepper = properties.security().apiKeyPepper();
    aliceTrade = fx.apiKey(alice, Scopes.ALL, pepper);
    aliceRead = fx.apiKey(alice, Set.of(Scopes.ACCOUNT_READ, Scopes.MARKET_READ), pepper);
    bobTrade = fx.apiKey(bob, Scopes.ALL, pepper);
    botKey = fx.apiKey(bot, Scopes.ALL, pepper);
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

  static final String AAPL_230 = "{\"symbol\":\"aapl\",\"condition\":\"PRICE_GTE\",\"threshold\":\"230.00\"}";

  @Test
  void createIsIdempotentAndReadsNeedOnlyAccountRead() throws Exception {
    MockHttpServletResponse created = bearer(aliceTrade, json(post("/api/v1/alerts"), AAPL_230));
    assertThat(created.getStatus()).isEqualTo(201);
    String id = read(created, "$.id");
    assertThat(id).startsWith("alr_");
    assertThat((String) read(created, "$.threshold")).isEqualTo("230.0000");
    assertThat((String) read(created, "$.status")).isEqualTo("ACTIVE");
    assertThat((Boolean) read(created, "$.repeat")).isFalse();
    assertThat(created.getContentAsString()).contains("\"note\":null").contains("\"lastTriggerPrice\":null");

    MockHttpServletResponse same = bearer(aliceTrade, json(post("/api/v1/alerts"),
        "{\"symbol\":\"AAPL\",\"condition\":\"price_gte\",\"threshold\":230}"));
    assertThat(same.getStatus()).isEqualTo(200);
    assertThat((String) read(same, "$.id")).isEqualTo(id);

    MockHttpServletResponse list = bearer(aliceRead, get("/api/v1/alerts?status=active&symbol=AAPL"));
    assertThat(list.getStatus()).isEqualTo(200);
    assertThat((List<String>) read(list, "$[*].id")).containsExactly(id);
    MockHttpServletResponse denied = bearer(aliceRead, json(post("/api/v1/alerts"), AAPL_230));
    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat((String) read(denied, "$.code")).isEqualTo("FORBIDDEN_SCOPE");
    assertThat(bearer(aliceRead, delete("/api/v1/alerts/" + id)).getStatus()).isEqualTo(403);
  }

  @Test
  void editPauseResumeAndDelete() throws Exception {
    String id = read(bearer(aliceTrade, json(post("/api/v1/alerts"), AAPL_230)), "$.id");
    MockHttpServletResponse edited = bearer(aliceTrade, json(put("/api/v1/alerts/" + id),
        "{\"condition\":\"CHANGE_PCT_LTE\",\"threshold\":\"-0.03\",\"repeat\":true,\"note\":\"dip\"}"));
    assertThat(edited.getStatus()).isEqualTo(200);
    assertThat((String) read(edited, "$.condition")).isEqualTo("CHANGE_PCT_LTE");
    assertThat((String) read(edited, "$.threshold")).isEqualTo("-0.030000");
    assertThat((String) read(edited, "$.note")).isEqualTo("dip");
    assertThat((String) read(bearer(aliceTrade, post("/api/v1/alerts/" + id + "/pause")), "$.status"))
        .isEqualTo("PAUSED");
    assertThat((String) read(bearer(aliceTrade, post("/api/v1/alerts/" + id + "/resume")), "$.status"))
        .isEqualTo("ACTIVE");
    // another player's alert does not exist for them
    assertThat(bearer(bobTrade, post("/api/v1/alerts/" + id + "/pause")).getStatus()).isEqualTo(404);
    assertThat(bearer(bobTrade, delete("/api/v1/alerts/" + id)).getStatus()).isEqualTo(404);
    assertThat(bearer(aliceTrade, delete("/api/v1/alerts/" + id)).getStatus()).isEqualTo(204);
    MockHttpServletResponse gone = bearer(aliceTrade, delete("/api/v1/alerts/" + id));
    assertThat(gone.getStatus()).isEqualTo(404);
    assertThat((String) read(gone, "$.code")).isEqualTo("NOT_FOUND");
  }

  @Test
  void problemsHaveStableCodes() throws Exception {
    MockHttpServletResponse unknown = bearer(aliceTrade, json(post("/api/v1/alerts"),
        "{\"symbol\":\"ZZZZ\",\"condition\":\"PRICE_GTE\",\"threshold\":\"1\"}"));
    assertThat(unknown.getStatus()).isEqualTo(404);
    assertThat((String) read(unknown, "$.code")).isEqualTo("SYMBOL_NOT_FOUND");
    MockHttpServletResponse missing = bearer(aliceTrade, json(post("/api/v1/alerts"),
        "{\"symbol\":\"AAPL\",\"threshold\":\"1\"}"));
    assertThat(missing.getStatus()).isEqualTo(400);
    assertThat((String) read(missing, "$.code")).isEqualTo("VALIDATION_FAILED");
    MockHttpServletResponse bad = bearer(aliceTrade, json(post("/api/v1/alerts"),
        "{\"symbol\":\"AAPL\",\"condition\":\"PRICE_GTE\",\"threshold\":\"-5\"}"));
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat((List<String>) read(bad, "$.fields[*].field")).contains("threshold");
    MockHttpServletResponse badEnum = bearer(aliceTrade, json(post("/api/v1/alerts"),
        "{\"symbol\":\"AAPL\",\"condition\":\"PRICE_SOMETIMES\",\"threshold\":\"5\"}"));
    assertThat(badEnum.getStatus()).isEqualTo(400);
    for (int i = 1; i <= 50; i++) {
      assertThat(bearer(bobTrade, json(post("/api/v1/alerts"),
          "{\"symbol\":\"NVDA\",\"condition\":\"PRICE_GTE\",\"threshold\":\"" + i + "\"}")).getStatus()).isEqualTo(201);
    }
    MockHttpServletResponse full = bearer(bobTrade, json(post("/api/v1/alerts"),
        "{\"symbol\":\"NVDA\",\"condition\":\"PRICE_GTE\",\"threshold\":\"51\"}"));
    assertThat(full.getStatus()).isEqualTo(422);
    assertThat((String) read(full, "$.code")).isEqualTo("TOO_MANY_ALERTS");
  }

  @Test
  void botsUseAlertsLikeAnyPlayer() throws Exception {
    MockHttpServletResponse r = bearer(botKey, json(post("/api/v1/alerts"),
        "{\"symbol\":\"TSLA\",\"condition\":\"CHANGE_PCT_GTE\",\"threshold\":\"0.05\"}"));
    assertThat(r.getStatus()).isEqualTo(201);
    assertThat(bearer(botKey, get("/api/v1/alerts")).getContentAsString()).contains("TSLA");
    assertThat(bearer(aliceTrade, get("/api/v1/alerts")).getContentAsString()).doesNotContain("TSLA");
  }
}
