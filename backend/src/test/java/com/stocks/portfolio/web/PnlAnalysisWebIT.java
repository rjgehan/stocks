package com.stocks.portfolio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.contract.security.Scopes;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 2 {@code GET /account/pnl-analysis} and {@code GET /account/pnl-calendar} (§16.10) through the real chains:
 * the caller's active run, {@code account:read}, §6.3 JSON, parameter validation and the extra benchmark (QQQ) by
 * default.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class PnlAnalysisWebIT {

  static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired ValuationService valuation;
  @Autowired PerformanceProperties performanceProperties;
  @Autowired StocksProperties properties;

  PortfolioData data;
  PlayerRef alice;
  AccountRef acc;

  @BeforeEach
  void setUp() {
    time.set(Instant.parse("2026-10-05T14:00:00Z"));
    data = new PortfolioData(jdbc, time, valuation);
    alice = data.human("pnl" + SEQ.incrementAndGet());
    acc = data.open(alice, "500.00");
    time.set(Instant.parse("2026-10-05T15:00:00Z"));
    data.deposit(acc, "100.00");
  }

  MockHttpServletResponse as(PlayerRef p, MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.with(TestPrincipals.as(p))).andReturn().getResponse();
  }

  @Test
  void theAnalysisCoversTheCallersRunAgainstSpyAndQqq() throws Exception {
    MockHttpServletResponse r = as(alice, get("/api/v1/account/pnl-analysis").param("range", "all"));
    assertThat(r.getStatus()).isEqualTo(200);
    String body = r.getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.range")).isEqualTo("ALL");
    assertThat(JsonPath.<String>read(body, "$.pnl")).isEqualTo("0.00");
    assertThat(JsonPath.<String>read(body, "$.return")).isEqualTo("0.0000000000");
    assertThat(JsonPath.<List<String>>read(body, "$.benchmarks[*].symbol")).containsExactly("SPY", "QQQ");
    assertThat(JsonPath.<List<Object>>read(body, "$.bySymbol")).isEmpty();
    assertThat(JsonPath.<String>read(body, "$.other")).isEqualTo("0.00");
    assertThat(JsonPath.<String>read(as(alice, get("/api/v1/account/pnl-analysis")).getContentAsString(),
        "$.range")).isEqualTo("1M");
    assertThat(performanceProperties.extraBenchmarkSymbols()).containsExactly("QQQ");

    String marketOnly = data.fx.apiKey(alice, Set.of(Scopes.MARKET_READ), properties.security().apiKeyPepper());
    for (String path : List.of("/api/v1/account/pnl-analysis", "/api/v1/account/pnl-calendar")) {
      assertThat(mvc.perform(get(path).header("Authorization", "Bearer " + marketOnly)).andReturn().getResponse()
          .getStatus()).as(path).isEqualTo(403);
    }
    PlayerRef newcomer = data.human("nopnl" + SEQ.incrementAndGet());
    MockHttpServletResponse none = as(newcomer, get("/api/v1/account/pnl-calendar"));
    assertThat(none.getStatus()).isEqualTo(404);
    assertThat(JsonPath.<String>read(none.getContentAsString(), "$.code")).isEqualTo("ACCOUNT_NOT_FOUND");
    assertThat(as(newcomer, get("/api/v1/account/pnl-analysis")).getStatus()).isEqualTo(404);
  }

  @Test
  void theCalendarValidatesItsParameters() throws Exception {
    MockHttpServletResponse r = as(alice, get("/api/v1/account/pnl-calendar").param("month", "2026-10"));
    assertThat(r.getStatus()).isEqualTo(200);
    String body = r.getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.view")).isEqualTo("DAY");
    assertThat(JsonPath.<String>read(body, "$.from")).isEqualTo("2026-10-01");
    assertThat(JsonPath.<String>read(body, "$.to")).isEqualTo("2026-10-31");
    assertThat(JsonPath.<List<String>>read(body, "$.cells[*].date")).containsExactly("2026-10-05");
    assertThat(JsonPath.<String>read(body, "$.cells[0].pnl")).isEqualTo("0.00");
    assertThat(JsonPath.<String>read(body, "$.cells[0].flows")).as("the deposit is a flow").isEqualTo("100.00");
    assertThat(JsonPath.<Boolean>read(body, "$.cells[0].live")).isTrue();

    MockHttpServletResponse year = as(alice, get("/api/v1/account/pnl-calendar").param("view", "MONTH")
        .param("year", "2026"));
    assertThat(year.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<List<String>>read(year.getContentAsString(), "$.cells[*].date")).containsExactly("2026-10-01");
    assertThat(as(alice, get("/api/v1/account/pnl-calendar")).getStatus()).isEqualTo(200);

    for (String[] bad : List.of(new String[] {"month", "2026-13"}, new String[] {"month", "October"},
        new String[] {"month", "1999-01"}, new String[] {"year", "1990"}, new String[] {"view", "WEEK"})) {
      MockHttpServletResponse e = as(alice, get("/api/v1/account/pnl-calendar").param(bad[0], bad[1]));
      assertThat(e.getStatus()).as(bad[0] + "=" + bad[1]).isEqualTo(400);
      assertThat(JsonPath.<String>read(e.getContentAsString(), "$.code")).isEqualTo("VALIDATION_FAILED");
    }
  }
}
