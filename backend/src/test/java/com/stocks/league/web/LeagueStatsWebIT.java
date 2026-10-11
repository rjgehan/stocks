package com.stocks.league.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.contract.security.Scopes;
import com.stocks.league.FeedEventListener;
import com.stocks.league.LeaderboardServiceImpl;
import com.stocks.portfolio.PortfolioData;
import com.stocks.portfolio.ScriptedMarketConfig;
import com.stocks.testkit.Fixtures.AccountRef;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.ScriptedMarketData;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.TestPrincipals;
import java.time.Duration;
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
 * Phase 2 league endpoints (§16.8, §16.9, §16.11; §18.2) through the real filter chains and Boot's
 * {@code JsonMapper}: the race chart and player stats need {@code league:read}, your own stats {@code account:read},
 * reactions {@code trade}; JSON per §6.3 (decimal strings, nulls present); errors per §6.5.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, ScriptedMarketConfig.class})
class LeagueStatsWebIT {

  static final AtomicInteger SEQ = new AtomicInteger();
  static final Instant MON_10 = Instant.parse("2026-10-05T14:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired ValuationService valuation;
  @Autowired MarketDataService market;
  @Autowired LeaderboardServiceImpl leaderboard;
  @Autowired FeedEventListener feed;
  @Autowired StocksProperties properties;

  PortfolioData data;
  PlayerRef alice;
  PlayerRef bot;
  AccountRef aliceAcc;
  String aliceHandle;
  String botHandle;
  String itemId;

  @BeforeEach
  void setUp() {
    time.set(MON_10);
    data = new PortfolioData(jdbc, time, valuation);
    int n = SEQ.incrementAndGet();
    aliceHandle = "statsalice" + n;
    botHandle = "statsbot" + n;
    alice = data.human(aliceHandle);
    bot = data.ai(botHandle, alice);
    aliceAcc = data.open(alice, "500.00");
    AccountRef botAcc = data.open(bot, "500.00");
    String exe = data.trade(botAcc, "BUY", "SIMA", "2", "100.00", "MCP_KEY", "Public bot thesis", true);
    feed.recordExecution(exe);
    data.buy(aliceAcc, "SIMB", "1", "100.00");
    ((ScriptedMarketData) market).print("SIMA", MON_10.plusSeconds(30), "110.00", 10);
    time.set(MON_10.plus(Duration.ofMinutes(20)));
    leaderboard.refresh();
    itemId = jdbc.sql("SELECT public_id FROM feed_event WHERE source_ref = ?").param("EXEC:" + exe)
        .query(String.class).single();
  }

  MockHttpServletResponse as(PlayerRef p, MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.with(TestPrincipals.as(p))).andReturn().getResponse();
  }

  MockHttpServletResponse bearer(String token, MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.header("Authorization", "Bearer " + token)).andReturn().getResponse();
  }

  static Object json(MockHttpServletResponse r, String path) throws Exception {
    return JsonPath.read(r.getContentAsString(), path);
  }

  @Test
  void theRaceChartAndStatsServeTheBoardsNumbers() throws Exception {
    MockHttpServletResponse race = as(alice, get("/api/v1/league/race").param("period", "today"));
    assertThat(race.getStatus()).isEqualTo(200);
    assertThat(json(race, "$.period")).isEqualTo("TODAY");
    assertThat(json(race, "$.kind")).isEqualTo("ALL");
    List<String> handles = JsonPath.read(race.getContentAsString(), "$.series[*].player.handle");
    assertThat(handles).contains(aliceHandle, botHandle);
    List<String> botReturn = JsonPath.read(race.getContentAsString(),
        "$.series[?(@.player.handle == '" + botHandle + "')].return");
    assertThat(botReturn).containsExactly("0.0400000000");                 // 2 × +10 on $500, a decimal string
    assertThat(race.getContentAsString()).contains("\"benchmark\":{\"symbol\":\"SPY\"");
    assertThat(as(alice, get("/api/v1/league/race")).getStatus()).isEqualTo(200);
    assertThat(as(alice, get("/api/v1/league/race").param("period", "DECADE")).getStatus()).isEqualTo(400);

    MockHttpServletResponse stats = as(alice, get("/api/v1/players/" + botHandle + "/stats").param("period", "TODAY"));
    assertThat(stats.getStatus()).isEqualTo(200);
    assertThat(json(stats, "$.player.handle")).isEqualTo(botHandle);
    assertThat(json(stats, "$.return")).isEqualTo("0.0400000000");
    assertThat(json(stats, "$.sharpe")).isNull();
    assertThat(json(stats, "$.sharpeMinDays")).isEqualTo(20);
    assertThat(json(stats, "$.bestTrade")).isNull();
    assertThat(stats.getContentAsString()).contains("\"profitFactor\":null").contains("\"rankHistory\":[]");
    assertThat(json(as(alice, get("/api/v1/players/" + botHandle + "/stats")), "$.period")).isEqualTo("ALL");
    MockHttpServletResponse missing = as(alice, get("/api/v1/players/nobody_here/stats"));
    assertThat(missing.getStatus()).isEqualTo(404);
    assertThat(json(missing, "$.code")).isEqualTo("PLAYER_NOT_FOUND");

    MockHttpServletResponse mine = as(alice, get("/api/v1/account/stats").param("period", "WEEK"));
    assertThat(mine.getStatus()).isEqualTo(200);
    assertThat(json(mine, "$.player.handle")).isEqualTo(aliceHandle);
    assertThat(json(mine, "$.period")).isEqualTo("WEEK");
    assertThat(json(mine, "$.partialPeriod")).isEqualTo(true);

    // the leaderboard row carries the new columns
    MockHttpServletResponse board = as(alice, get("/api/v1/leaderboard").param("period", "ALL"));
    assertThat(board.getContentAsString()).contains("\"sharpe\":null").contains("\"profitFactor\":null");
  }

  @Test
  void scopesFollowTheRoutes() throws Exception {
    String pepper = properties.security().apiKeyPepper();
    String marketOnly = data.fx.apiKey(bot, Set.of(Scopes.MARKET_READ), pepper);
    for (String path : List.of("/api/v1/league/race", "/api/v1/players/" + aliceHandle + "/stats",
        "/api/v1/account/stats")) {
      MockHttpServletResponse r = bearer(marketOnly, get(path));
      assertThat(r.getStatus()).as(path).isEqualTo(403);
      assertThat(json(r, "$.code")).isEqualTo("FORBIDDEN_SCOPE");
    }
    String league = data.fx.apiKey(bot, Set.of(Scopes.LEAGUE_READ), pepper);
    assertThat(bearer(league, get("/api/v1/league/race")).getStatus()).isEqualTo(200);
    assertThat(bearer(league, get("/api/v1/players/" + aliceHandle + "/stats")).getStatus()).isEqualTo(200);
    assertThat(bearer(league, get("/api/v1/account/stats")).getStatus()).isEqualTo(403);
    String account = data.fx.apiKey(bot, Set.of(Scopes.ACCOUNT_READ), pepper);
    assertThat(bearer(account, get("/api/v1/account/stats")).getStatus()).isEqualTo(200);
    // a read-only key cannot react
    String readOnly = data.fx.apiKey(bot, Set.of(Scopes.MARKET_READ, Scopes.ACCOUNT_READ, Scopes.LEAGUE_READ),
        pepper);
    MockHttpServletResponse r = bearer(readOnly, put("/api/v1/feed/" + itemId + "/reaction")
        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"ROCKET\"}"));
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(bearer(readOnly, delete("/api/v1/feed/" + itemId + "/reaction")).getStatus()).isEqualTo(403);
  }

  @Test
  void reactionsAreSetReplacedAndRemoved() throws Exception {
    String key = data.fx.apiKey(bot, properties.security().apiKeyPepper());
    MockHttpServletResponse r = bearer(key, put("/api/v1/feed/" + itemId + "/reaction")
        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"ROCKET\"}"));
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(json(r, "$.itemId")).isEqualTo(itemId);
    assertThat(json(r, "$.reactions[0].reaction")).isEqualTo("ROCKET");
    assertThat(json(r, "$.reactions[0].count")).isEqualTo(1);
    assertThat(json(r, "$.reactions[0].players[0].handle")).isEqualTo(botHandle);
    assertThat(json(r, "$.reactions[0].players[0].kind")).isEqualTo("AI");

    // alice on her browser session (CSRF) picks fire
    r = as(alice, put("/api/v1/feed/" + itemId + "/reaction").with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"FIRE\"}"));
    assertThat(r.getStatus()).isEqualTo(200);
    List<String> emojis = JsonPath.read(r.getContentAsString(), "$.reactions[*].reaction");
    assertThat(emojis).containsExactly("ROCKET", "FIRE");
    MockHttpServletResponse feedPage = as(alice, get("/api/v1/feed").param("limit", "200"));
    List<Object> onItem = JsonPath.read(feedPage.getContentAsString(),
        "$.items[?(@.id == '" + itemId + "')].reactions[*].reaction");
    assertThat(onItem).containsExactly("ROCKET", "FIRE");

    r = bearer(key, delete("/api/v1/feed/" + itemId + "/reaction"));
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<List<String>>read(r.getContentAsString(), "$.reactions[*].reaction")).containsExactly("FIRE");
    assertThat(bearer(key, delete("/api/v1/feed/" + itemId + "/reaction")).getStatus()).isEqualTo(200);

    MockHttpServletResponse bad = bearer(key, put("/api/v1/feed/" + itemId + "/reaction")
        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"HEART\"}"));
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat(json(bad, "$.code")).isEqualTo("VALIDATION_FAILED");
    MockHttpServletResponse none = bearer(key, put("/api/v1/feed/" + itemId + "/reaction")
        .contentType(MediaType.APPLICATION_JSON).content("{}"));
    assertThat(none.getStatus()).isEqualTo(400);
    MockHttpServletResponse unknown = bearer(key, put("/api/v1/feed/fde_nope/reaction")
        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"GEM\"}"));
    assertThat(unknown.getStatus()).isEqualTo(404);
    assertThat(json(unknown, "$.code")).isEqualTo("NOT_FOUND");
  }
}
