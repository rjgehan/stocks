package com.stocks.marketdata.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.ProviderStatus;
import com.stocks.contract.security.Scopes;
import com.stocks.jobs.JobScheduler;
import com.stocks.marketdata.alpaca.AlpacaCapabilityProbe;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.WebSession;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * §15.9 outages: the {@code hybrid} profile with (dummy) keys and an unreachable Alpaca boots, serves the bundled
 * calendar and the universe assets, answers quotes with quality NONE, and reports the feed DOWN, without crashing or
 * stalling requests.
 */
@SpringBootTest(properties = {
    "stocks.marketdata.profile=hybrid",
    "stocks.marketdata.alpaca.key-id=dummy-key",
    "stocks.marketdata.alpaca.secret-key=dummy-secret",
    "stocks.marketdata.alpaca.data-url=http://127.0.0.1:1",
    "stocks.marketdata.alpaca.trading-url=http://127.0.0.1:1",
    "stocks.marketdata.alpaca.stream-url=ws://127.0.0.1:1/v2/iex",
    "stocks.marketdata.finnhub.token="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)   // a unique context: release its pool
@Import(TestClockConfig.class)
class HybridProfileBootIT {

  @Autowired MockMvc mvc;
  @Autowired MarketDataService marketData;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;
  @Autowired JobScheduler scheduler;
  @Autowired AlpacaCapabilityProbe probe;

  String json(String url, String key) throws Exception {
    var r = mvc.perform(get(url).header("Authorization", "Bearer " + key)).andReturn();
    assertThat(r.getResponse().getStatus()).as(url).isEqualTo(200);
    return WebSession.contentOf(r);
  }

  @Test
  void bootsAndDegrades() throws Exception {
    Fixtures fx = new Fixtures(jdbc, time);
    String key = fx.apiKey(fx.human("hybrid1"), Set.of(Scopes.MARKET_READ), properties.security().apiKeyPepper());
    assertThat(marketData.quote("AAPL").quality()).isEqualTo(DataQuality.NONE);
    assertThat(marketData.asset("AAPL")).isPresent();                       // seeded from the universe
    String clock = json("/api/v1/market/clock", key);
    assertThat((String) JsonPath.read(clock, "$.feed.profile")).isEqualTo("hybrid");
    assertThat((String) JsonPath.read(clock, "$.feed.status")).isEqualTo("DOWN");
    assertThat((String) JsonPath.read(clock, "$.feed.quality")).isEqualTo("NONE");
    assertThat((String) JsonPath.read(clock, "$.session")).isEqualTo("REGULAR");
    String q = json("/api/v1/quotes?symbols=AAPL,SPY", key);
    assertThat((String) JsonPath.read(q, "$.quotes[0].quality")).isEqualTo("NONE");
    assertThat((String) JsonPath.read(q, "$.quotes[0].source")).isEqualTo("alpaca");
    assertThat((java.util.List<Object>) JsonPath.read(json("/api/v1/bars/AAPL?range=1D", key), "$.bars")).isEmpty();
    assertThat((java.util.List<Object>) JsonPath.read(json("/api/v1/news", key), "$.items")).isEmpty();
    json("/api/v1/market/movers", key);
    assertThat(json("/api/v1/assets/AAPL", key)).contains("\"fundamentals\":null");
    ProviderStatus alpaca = marketData.status().providers().getFirst();
    assertThat(alpaca.id()).isEqualTo("alpaca");
    assertThat(alpaca.state()).isIn("DOWN", "BACKOFF", "CONNECTING");
    assertThat(alpaca.streamSymbolsMax()).isEqualTo(30);
    assertThat(alpaca.restPerMinMax()).isEqualTo(180);
    assertThat(probe.probe()).containsEntry("snapshotsIEX", "unreachable");
    assertThat(scheduler.jobs()).extracting(j -> j.name()).contains("ALPACA_PROBE", "CALENDAR_SYNC");
    assertThat(marketData.executableQuote("AAPL", time.now(), com.stocks.common.domain.SessionType.REGULAR)).isEmpty();
  }
}
