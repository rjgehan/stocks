package com.stocks.portfolio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.watchlist.WatchlistService;
import com.stocks.portfolio.PortfolioData;
import com.stocks.portfolio.config.PerformanceProperties;
import com.stocks.testkit.Fixtures.AccountRef;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.TestPrincipals;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /account/history} through the real chains, plus the WS4 contract beans the rest of the app calls
 * (WS3's {@code Account} DTO uses {@code accountPnl}/{@code positionDayPnl}; signup uses {@code seedDefault}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class PerformanceWebIT {

  static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired ValuationService valuation;
  @Autowired PerformanceQueries performance;
  @Autowired WatchlistService watchlists;
  @Autowired PerformanceProperties performanceProperties;
  @Autowired StocksProperties properties;

  PortfolioData data;
  PlayerRef alice;
  AccountRef acc;

  @BeforeEach
  void setUp() {
    time.set(Instant.parse("2026-10-05T14:00:00Z"));
    data = new PortfolioData(jdbc, time, valuation);
    alice = data.human("hist" + SEQ.incrementAndGet());
    acc = data.open(alice, "500.00");
    time.set(Instant.parse("2026-10-05T15:00:00Z"));
    data.deposit(acc, "100.00");
  }

  @Test
  void theAccountHistoryIsTheCallersRunRelativeCurve() throws Exception {
    MockHttpServletResponse r = mvc.perform(get("/api/v1/account/history").param("range", "all")
        .with(TestPrincipals.as(alice))).andReturn().getResponse();
    assertThat(r.getStatus()).isEqualTo(200);
    String body = r.getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.range")).isEqualTo("ALL");
    List<Object> points = JsonPath.read(body, "$.points");
    assertThat(points).hasSize(2);                                  // opening, live
    assertThat(JsonPath.<String>read(body, "$.points[1].equity")).isEqualTo("600.00");
    assertThat(JsonPath.<String>read(body, "$.points[1].netContributions")).isEqualTo("600.00");
    assertThat(JsonPath.<String>read(body, "$.summary.return")).isEqualTo("0.0000000000");
    assertThat(JsonPath.<String>read(body, "$.summary.pnl")).isEqualTo("0.00");
    assertThat(JsonPath.<String>read(body, "$.flows[0].type")).isEqualTo("DEPOSIT");
    assertThat(JsonPath.<Object>read(body, "$.summary.excessVsBenchmark")).isNull();
    MockHttpServletResponse byDefault = mvc.perform(get("/api/v1/account/history").with(TestPrincipals.as(alice)))
        .andReturn().getResponse();
    assertThat(JsonPath.<String>read(byDefault.getContentAsString(), "$.range")).isEqualTo("1M");

    String marketOnly = data.fx.apiKey(alice, Set.of(Scopes.MARKET_READ), properties.security().apiKeyPepper());
    assertThat(mvc.perform(get("/api/v1/account/history").header("Authorization", "Bearer " + marketOnly))
        .andReturn().getResponse().getStatus()).isEqualTo(403);

    PlayerRef newcomer = data.human("nohist" + SEQ.incrementAndGet());
    MockHttpServletResponse none = mvc.perform(get("/api/v1/account/history").with(TestPrincipals.as(newcomer)))
        .andReturn().getResponse();
    assertThat(none.getStatus()).isEqualTo(404);
    assertThat(JsonPath.<String>read(none.getContentAsString(), "$.code")).isEqualTo("ACCOUNT_NOT_FOUND");
  }

  @Test
  void theContractBeansAreWiredForTheOtherWorkstreams() {
    assertThat(performance.accountPnl(acc.id()).totalPnl()).isEqualByComparingTo("0.00");
    assertThat(performance.positionDayPnl(acc.id())).isEmpty();
    assertThat(valuation.valueAllActive()).containsKey(acc.id());
    watchlists.seedDefault(alice.id());
    assertThat(watchlists.list(alice.id())).singleElement().satisfies(w -> assertThat(w.name()).isEqualTo("My List"));
    assertThat(performanceProperties.leaderboardRefresh()).hasSeconds(5);
    assertThat(performanceProperties.benchmarkSymbol()).isEqualTo("SPY");
  }
}
