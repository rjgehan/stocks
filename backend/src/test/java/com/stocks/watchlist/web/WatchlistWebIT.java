package com.stocks.watchlist.web;

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
import com.stocks.testkit.TestPrincipals;
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
 * §18.2 {@code /watchlists/**} through the real chains: reads with {@code account:read}, writes with {@code trade}
 * (§17.1), status codes (201 create, 204 delete), problem codes, and per-player ownership.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, ScriptedMarketConfig.class})
class WatchlistWebIT {

  static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;

  Fixtures fx;
  PlayerRef alice;
  PlayerRef bob;
  String aliceTrade;
  String aliceRead;

  @BeforeEach
  void setUp() {
    fx = new Fixtures(jdbc, time);
    int n = SEQ.incrementAndGet();
    alice = fx.human("wl_alice" + n);
    bob = fx.human("wl_bob" + n);
    String pepper = properties.security().apiKeyPepper();
    aliceTrade = fx.apiKey(alice, Scopes.ALL, pepper);
    aliceRead = fx.apiKey(alice, Set.of(Scopes.ACCOUNT_READ, Scopes.MARKET_READ), pepper);
  }

  MockHttpServletResponse bearer(String token, MockHttpServletRequestBuilder r) throws Exception {
    return mvc.perform(r.header("Authorization", "Bearer " + token)).andReturn().getResponse();
  }

  static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, String body) {
    return r.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  static Object read(MockHttpServletResponse r, String path) throws Exception {
    return JsonPath.read(r.getContentAsString(), path);
  }

  @Test
  void theFullLifecycle() throws Exception {
    MockHttpServletResponse created = bearer(aliceTrade, json(post("/api/v1/watchlists"),
        "{\"name\":\"Tech\",\"symbols\":[\"aapl\",\"NVDA\"]}"));
    assertThat(created.getStatus()).isEqualTo(201);
    String id = (String) read(created, "$.id");
    assertThat(id).startsWith("wlt_");
    assertThat(read(created, "$.symbols")).isEqualTo(List.of("AAPL", "NVDA"));
    assertThat(read(created, "$.sortOrder")).isEqualTo(0);

    MockHttpServletResponse added = bearer(aliceTrade, json(post("/api/v1/watchlists/" + id + "/symbols"),
        "{\"symbol\":\"tsla\"}"));
    assertThat(added.getStatus()).isEqualTo(200);
    assertThat(read(added, "$.symbols")).isEqualTo(List.of("AAPL", "NVDA", "TSLA"));

    MockHttpServletResponse removed = bearer(aliceTrade, delete("/api/v1/watchlists/" + id + "/symbols/NVDA"));
    assertThat(removed.getStatus()).isEqualTo(200);
    assertThat(read(removed, "$.symbols")).isEqualTo(List.of("AAPL", "TSLA"));

    MockHttpServletResponse replaced = bearer(aliceTrade, json(put("/api/v1/watchlists/" + id),
        "{\"name\":\"Faves\",\"symbols\":[\"TSLA\",\"AAPL\"]}"));
    assertThat(replaced.getStatus()).isEqualTo(200);
    assertThat(read(replaced, "$.name")).isEqualTo("Faves");
    assertThat(read(replaced, "$.symbols")).isEqualTo(List.of("TSLA", "AAPL"));

    MockHttpServletResponse list = bearer(aliceRead, get("/api/v1/watchlists"));
    assertThat(list.getStatus()).isEqualTo(200);
    assertThat(read(list, "$[0].name")).isEqualTo("Faves");
    MockHttpServletResponse session = mvc.perform(get("/api/v1/watchlists").with(TestPrincipals.as(alice)))
        .andReturn().getResponse();
    assertThat(read(session, "$[0].id")).isEqualTo(id);

    assertThat(bearer(aliceTrade, delete("/api/v1/watchlists/" + id)).getStatus()).isEqualTo(204);
    assertThat(read(bearer(aliceRead, get("/api/v1/watchlists")), "$.length()")).isEqualTo(0);
  }

  @Test
  void writesNeedTheTradeScopeAndListsAreOwned() throws Exception {
    MockHttpServletResponse denied = bearer(aliceRead, json(post("/api/v1/watchlists"), "{\"name\":\"X\"}"));
    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(read(denied, "$.code")).isEqualTo("FORBIDDEN_SCOPE");
    String id = (String) read(bearer(aliceTrade, json(post("/api/v1/watchlists"), "{\"name\":\"Mine\"}")), "$.id");
    String bobKey = fx.apiKey(bob, Scopes.ALL, properties.security().apiKeyPepper());
    MockHttpServletResponse foreign = bearer(bobKey, json(post("/api/v1/watchlists/" + id + "/symbols"),
        "{\"symbol\":\"AAPL\"}"));
    assertThat(foreign.getStatus()).isEqualTo(404);
    assertThat(read(foreign, "$.code")).isEqualTo("NOT_FOUND");
    assertThat(bearer(bobKey, delete("/api/v1/watchlists/" + id)).getStatus()).isEqualTo(404);
  }

  @Test
  void problemCodes() throws Exception {
    MockHttpServletResponse unknown = bearer(aliceTrade, json(post("/api/v1/watchlists"),
        "{\"name\":\"Bad\",\"symbols\":[\"NOPE\"]}"));
    assertThat(unknown.getStatus()).isEqualTo(404);
    assertThat(read(unknown, "$.code")).isEqualTo("SYMBOL_NOT_FOUND");
    MockHttpServletResponse blank = bearer(aliceTrade, json(post("/api/v1/watchlists"), "{\"name\":\"\"}"));
    assertThat(blank.getStatus()).isEqualTo(400);
    assertThat(read(blank, "$.code")).isEqualTo("VALIDATION_FAILED");
    MockHttpServletResponse noSymbols = bearer(aliceTrade, json(put("/api/v1/watchlists/wlt_x"), "{\"name\":\"A\"}"));
    assertThat(noSymbols.getStatus()).isEqualTo(400);
  }
}
