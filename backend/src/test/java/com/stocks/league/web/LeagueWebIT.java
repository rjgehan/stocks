package com.stocks.league.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * §18.2 league endpoints through the real filter chains and Boot's {@code JsonMapper}: scopes ({@code league:read}),
 * §6.3 JSON (decimal strings, nulls present), the deposit-aware board, public profiles and the delayed feed, and
 * that another player's private data (working orders, private notes, live positions) never appears.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, ScriptedMarketConfig.class})
class LeagueWebIT {

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
  AccountRef botAcc;
  String aliceHandle;
  String botHandle;

  @BeforeEach
  void setUp() {
    time.set(MON_10);
    data = new PortfolioData(jdbc, time, valuation);
    int n = SEQ.incrementAndGet();
    aliceHandle = "alice" + n;
    botHandle = "claude" + n;
    alice = data.human(aliceHandle);
    bot = data.ai(botHandle, alice);
    aliceAcc = data.open(alice, "500.00");
    botAcc = data.open(bot, "500.00");
    String exe = data.trade(botAcc, "BUY", "SIMA", "2", "100.00", "MCP_KEY", "Public bot thesis", true);
    feed.recordExecution(exe);
    data.trade(aliceAcc, "BUY", "SIMB", "1", "100.00", "WEB", "my secret plan", false);
    ((ScriptedMarketData) market).print("SIMA", MON_10.plusSeconds(30), "110.00", 10);
    time.set(MON_10.plus(Duration.ofMinutes(20)));
    data.deposit(aliceAcc, "250.00");
    leaderboard.refresh();
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
  void theLeaderboardIsDepositAwareAndSerializedPerSection6() throws Exception {
    MockHttpServletResponse r = as(alice, get("/api/v1/leaderboard").param("period", "today"));
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(json(r, "$.period")).isEqualTo("TODAY");
    assertThat(json(r, "$.metric")).isEqualTo("RETURN");
    assertThat(json(r, "$.kind")).isEqualTo("ALL");
    List<Map<String, Object>> rows = JsonPath.read(r.getContentAsString(), "$.rows[?(@.player.handle == '"
        + aliceHandle + "')]");
    assertThat(rows).singleElement().satisfies(row -> {
      assertThat(row.get("return")).isEqualTo("0.0000000000");       // a decimal string; the deposit moved nothing
      assertThat(row.get("netContributions")).isEqualTo("750.00");
      assertThat(row.get("flowsInPeriod")).isEqualTo("250.00");
      assertThat(row).containsKey("rankChange").containsEntry("rankChange", null);   // nulls are present
      assertThat(row).containsKey("excessVsBenchmark");
    });
    List<Map<String, Object>> botRows = JsonPath.read(r.getContentAsString(), "$.rows[?(@.player.handle == '"
        + botHandle + "')]");
    assertThat(botRows).singleElement().satisfies(row -> {
      assertThat(row.get("return")).isEqualTo("0.0400000000");       // 2 × 110 + 300 = 520
      assertThat(((Map<?, ?>) row.get("player")).get("kind")).isEqualTo("AI");
      assertThat(((Map<?, ?>) row.get("player")).get("aiModel")).isEqualTo("Claude");
    });
    assertThat(json(r, "$.me.player.handle")).isEqualTo(aliceHandle);
    assertThat(as(alice, get("/api/v1/leaderboard").param("limit", "0")).getStatus()).isEqualTo(400);
    MockHttpServletResponse bad = as(alice, get("/api/v1/leaderboard").param("period", "DECADE"));
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat(json(bad, "$.code")).isEqualTo("VALIDATION_FAILED");
    assertThat(as(alice, get("/api/v1/leaderboard").param("kind", "ai").param("metric", "pnl")).getStatus())
        .isEqualTo(200);
  }

  @Test
  void leagueRoutesNeedTheLeagueReadScope() throws Exception {
    String pepper = properties.security().apiKeyPepper();
    String marketOnly = data.fx.apiKey(bot, Set.of(Scopes.MARKET_READ), pepper);
    for (String path : List.of("/api/v1/leaderboard", "/api/v1/players", "/api/v1/players/" + botHandle,
        "/api/v1/feed")) {
      MockHttpServletResponse r = bearer(marketOnly, get(path));
      assertThat(r.getStatus()).as(path).isEqualTo(403);
      assertThat(json(r, "$.code")).isEqualTo("FORBIDDEN_SCOPE");
    }
    String league = data.fx.apiKey(bot, Set.of(Scopes.LEAGUE_READ), pepper);
    assertThat(bearer(league, get("/api/v1/leaderboard")).getStatus()).isEqualTo(200);
    assertThat(mvc.perform(get("/api/v1/leaderboard")).andReturn().getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void playersAndProfilesShowOnlyPublicData() throws Exception {
    MockHttpServletResponse list = as(alice, get("/api/v1/players").param("kind", "AI"));
    assertThat(list.getStatus()).isEqualTo(200);
    List<String> handles = JsonPath.read(list.getContentAsString(), "$[*].handle");
    assertThat(handles).contains(botHandle).doesNotContain(aliceHandle);

    MockHttpServletResponse profile = as(bot, get("/api/v1/players/" + aliceHandle.toUpperCase()));
    assertThat(profile.getStatus()).isEqualTo(200);
    String body = profile.getContentAsString();
    assertThat(json(profile, "$.player.handle")).isEqualTo(aliceHandle);
    assertThat(json(profile, "$.netContributions")).isEqualTo("750.00");
    assertThat(json(profile, "$.deposits.count")).isEqualTo(1);
    assertThat(json(profile, "$.stats.TODAY.return")).isNotNull();
    assertThat(body).doesNotContain("secret");                     // a private note never leaks
    assertThat(body).doesNotContain("orderId").doesNotContain("clientOrderId").doesNotContain("reservedCash");
    List<Object> recent = JsonPath.read(body, "$.recentTrades");
    assertThat(recent).hasSize(1);                                  // her trade is older than 15 minutes now
    assertThat(json(profile, "$.recentTrades[0].untrusted.rationale")).isNull();

    MockHttpServletResponse botProfile = as(alice, get("/api/v1/players/" + botHandle));
    assertThat(json(botProfile, "$.ownerHandle")).isEqualTo(aliceHandle);
    assertThat(json(botProfile, "$.recentTrades[0].viaApi")).isEqualTo(true);
    assertThat(json(botProfile, "$.recentTrades[0].untrusted.rationale")).isEqualTo("Public bot thesis");

    MockHttpServletResponse missing = as(alice, get("/api/v1/players/nobody_here"));
    assertThat(missing.getStatus()).isEqualTo(404);
    assertThat(json(missing, "$.code")).isEqualTo("PLAYER_NOT_FOUND");

    MockHttpServletResponse history = as(alice, get("/api/v1/players/" + botHandle + "/history").param("range", "1w"));
    assertThat(history.getStatus()).isEqualTo(200);
    assertThat(json(history, "$.range")).isEqualTo("1W");
    List<Object> points = JsonPath.read(history.getContentAsString(), "$.points");
    assertThat(points).isNotEmpty();

    MockHttpServletResponse trades = as(alice, get("/api/v1/players/" + botHandle + "/trades").param("limit", "1"));
    assertThat(trades.getStatus()).isEqualTo(200);
    assertThat(json(trades, "$.items[0].symbol")).isEqualTo("SIMA");
    assertThat(json(trades, "$.items[0].price")).isEqualTo("100.0000");
    assertThat(as(alice, get("/api/v1/players/" + botHandle + "/trades").param("limit", "500")).getStatus())
        .isEqualTo(400);
    assertThat(as(alice, get("/api/v1/players/" + botHandle + "/trades").param("cursor", "%%%")).getStatus())
        .isEqualTo(400);
  }

  @Test
  void theFeedIsDelayedAndPaged() throws Exception {
    MockHttpServletResponse r = as(alice, get("/api/v1/feed").param("limit", "50"));
    assertThat(r.getStatus()).isEqualTo(200);
    List<Map<String, Object>> items = JsonPath.read(r.getContentAsString(), "$.items[?(@.player.handle == '"
        + botHandle + "')]");
    assertThat(items).singleElement().satisfies(i -> {
      assertThat(i.get("kind")).isEqualTo("FILL");
      assertThat(((Map<?, ?>) i.get("payload")).get("qty")).isEqualTo("2.00000");
      assertThat(((Map<?, ?>) ((Map<?, ?>) i.get("payload")).get("untrusted")).get("rationale"))
          .isEqualTo("Public bot thesis");
    });
    assertThat(as(alice, get("/api/v1/feed").param("limit", "0")).getStatus()).isEqualTo(400);
    assertThat(JsonPath.<Object>read(r.getContentAsString(), "$.nextCursor")).isNull();
  }
}
