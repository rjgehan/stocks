package com.stocks.marketdata.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.DailyMarkService;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.MarketUpdateListener;
import com.stocks.contract.security.Scopes;
import com.stocks.jobs.JobScheduler;
import com.stocks.marketdata.core.MarketDataServiceImpl;
import com.stocks.marketdata.testing.Await;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.WebSession;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The §18.2 market endpoints end to end on the {@code test} profile (SIM, fixed clock at Fri 2026-10-09 10:00 ET),
 * through the real security chain with API keys: shapes (§18.3, decimals as strings), validation, SYMBOL_NOT_FOUND,
 * the {@code market:read} scope, and the wiring (real MarketDataService, listeners, jobs).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)   // a unique context: release its pool
@Import({TestClockConfig.class, MarketControllersIT.Listener.class})
class MarketControllersIT {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @TestConfiguration(proxyBeanMethods = false)
  static class Listener {
    static final List<String> UPDATES = new CopyOnWriteArrayList<>();

    @Bean
    MarketUpdateListener recordingListener() {
      return UPDATES::add;
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;
  @Autowired MarketDataService marketData;
  @Autowired DailyMarkService dailyMarks;
  @Autowired MarketCalendar calendar;
  @Autowired JobScheduler scheduler;

  String key;

  @BeforeEach
  void setUp() {
    Fixtures fx = new Fixtures(jdbc, time);
    key = fx.apiKey(fx.human("md" + SEQ.incrementAndGet()), Set.of(Scopes.MARKET_READ),
        properties.security().apiKeyPepper());
  }

  MvcResult call(MockHttpServletRequestBuilder r) throws Exception {
    return mvc.perform(r.header("Authorization", "Bearer " + key)).andReturn();
  }

  String json(String url) throws Exception {
    MvcResult r = call(get(url));
    assertThat(r.getResponse().getStatus()).as(url).isEqualTo(200);
    return WebSession.contentOf(r);
  }

  static <T> T read(String json, String path) {
    return JsonPath.read(json, path);
  }

  String code(String url) throws Exception {
    return read(WebSession.contentOf(call(get(url))), "$.code");
  }

  @Test
  void wiring() {
    assertThat(marketData).isInstanceOf(MarketDataServiceImpl.class);
    assertThat(marketData.status().profile()).isEqualTo("simulated");
    assertThat(marketData.status().providers()).extracting(p -> p.id()).containsExactly("simulated");
    assertThat(dailyMarks).isNotNull();
    assertThat(calendar.isTradingDay(java.time.LocalDate.of(2026, 10, 12))).isTrue();
    assertThat(scheduler.jobs()).extracting(j -> j.name()).contains("ASSET_SYNC", "CALENDAR_SYNC", "DAILY_MARK",
        "DAILY_REMARK", "PREV_CLOSE", "SPLIT_GUARD", "HOLDING_LEASES", "MOVERS_REFRESH")
        .doesNotContain("ALPACA_PROBE");
    marketData.lease("SIMA", LeasePriority.VIEWING, "ws:it");
    Await.until(() -> Listener.UPDATES.contains("SIMA"), "a MarketUpdateListener callback for a leased symbol");
    marketData.releaseOwner("ws:it");
  }

  @Test
  void clock() throws Exception {
    String c = json("/api/v1/market/clock");
    assertThat((String) read(c, "$.session")).isEqualTo("REGULAR");
    assertThat((String) read(c, "$.tradingDate")).isEqualTo("2026-10-09");
    assertThat((String) read(c, "$.nowEt")).isEqualTo("2026-10-09T10:00:00-04:00");
    assertThat((String) read(c, "$.sessionEndsAt")).isEqualTo("2026-10-09T20:00:00.000Z");
    assertThat((String) read(c, "$.nextSession")).isEqualTo("POST");
    assertThat((String) read(c, "$.nextRegularOpen")).isEqualTo("2026-10-12T13:30:00.000Z");
    assertThat((String) read(c, "$.feed.profile")).isEqualTo("simulated");
    assertThat((String) read(c, "$.feed.quality")).isEqualTo("SIMULATED");
    assertThat((String) read(c, "$.feed.status")).isEqualTo("LIVE");
    assertThat((List<String>) read(c, "$.upcomingEarlyCloses")).contains("2026-11-27");
  }

  @Test
  void quotes() throws Exception {
    String q = json("/api/v1/quotes?symbols=aapl,ZZZZ");
    assertThat((String) read(q, "$.quotes[0].symbol")).isEqualTo("AAPL");
    assertThat((String) read(q, "$.quotes[0].quality")).isEqualTo("SIMULATED");
    assertThat((String) read(q, "$.quotes[0].bid")).matches("\\d+\\.\\d{4}");
    assertThat((String) read(q, "$.quotes[0].session")).isEqualTo("REGULAR");
    assertThat((Object) read(q, "$.quotes[0].ageMs")).isInstanceOf(Integer.class);
    assertThat((String) read(q, "$.quotes[1].quality")).isEqualTo("NONE");
    assertThat((Object) read(q, "$.quotes[1].last")).isNull();
    String many = String.join(",", java.util.stream.IntStream.range(0, 51).mapToObj(i -> "S" + i).toList());
    assertThat(code("/api/v1/quotes?symbols=" + many)).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/quotes?symbols=A$B")).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/quotes?symbols=")).isEqualTo("VALIDATION_FAILED");
    assertThat((List<Object>) read(json("/api/v1/market/indices"), "$[*].symbol")).containsExactly("SPY", "QQQ", "DIA",
        "IWM");
  }

  @Test
  void calendarEndpoint() throws Exception {
    String days = json("/api/v1/market/calendar?from=2026-10-09&to=2026-10-13");
    assertThat((List<Object>) read(days, "$[*].date")).containsExactly("2026-10-09", "2026-10-12", "2026-10-13");
    assertThat((String) read(days, "$[0].settlementDate")).isEqualTo("2026-10-13");
    assertThat((Boolean) read(days, "$[0].earlyClose")).isFalse();
    assertThat(code("/api/v1/market/calendar?from=2026-01-01&to=2027-06-01")).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/market/calendar?from=2026-10-09&to=2026-10-01")).isEqualTo("VALIDATION_FAILED");
    assertThat((List<Object>) read(json("/api/v1/market/calendar"), "$")).isNotEmpty();
  }

  @Test
  void moversAndNews() throws Exception {
    String m = json("/api/v1/market/movers?list=losers&limit=5");
    assertThat((String) read(m, "$.list")).isEqualTo("LOSERS");
    List<Map<String, Object>> items = read(m, "$.items");
    assertThat(items).hasSizeLessThanOrEqualTo(5).allSatisfy(i -> {
      assertThat(i.get("name")).isNotNull();
      assertThat((String) i.get("changePct")).startsWith("-");
    });
    assertThat(code("/api/v1/market/movers?limit=51")).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/market/movers?list=BOGUS")).isEqualTo("VALIDATION_FAILED");
    String n = json("/api/v1/news?symbols=AAPL&limit=2");
    assertThat((List<Object>) read(n, "$.items")).hasSize(2);
    assertThat((String) read(n, "$.items[0].untrusted.headline")).contains("AAPL");
    assertThat(code("/api/v1/news?limit=21")).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void assets() throws Exception {
    String s = json("/api/v1/assets/search?q=apple&limit=5");
    assertThat((List<Object>) read(s, "$[*].symbol")).contains("AAPL");
    String a = json("/api/v1/assets/AAPL");
    assertThat((Boolean) read(a, "$.fractionable")).isTrue();
    assertThat((String) read(a, "$.liquidityTier")).isEqualTo("MEGA");
    assertThat((String) read(a, "$.logoUrl")).isEqualTo("/api/v1/assets/AAPL/logo");
    assertThat((String) read(a, "$.fundamentals.source")).isEqualTo("simulated");
    assertThat((String) read(a, "$.week52High")).isNotNull();
    assertThat(code("/api/v1/assets/NOPE")).isEqualTo("SYMBOL_NOT_FOUND");
    assertThat(code("/api/v1/assets/search?q=a&limit=21")).isEqualTo("VALIDATION_FAILED");
    MvcResult logo = call(get("/api/v1/assets/AAPL/logo"));
    assertThat(logo.getResponse().getContentType()).isEqualTo("image/png");
    assertThat(logo.getResponse().getHeader("Cache-Control")).contains("max-age=31536000");
    assertThat(logo.getResponse().getContentAsByteArray()).startsWith((byte) 0x89, (byte) 'P');
    // review regression: an unknown symbol never reaches the provider and its placeholder is not pinned for a year
    MvcResult unknown = call(get("/api/v1/assets/ZZZZQ/logo"));
    assertThat(unknown.getResponse().getStatus()).isEqualTo(200);
    assertThat(unknown.getResponse().getHeader("Cache-Control")).contains("max-age=3600");
  }

  @Test
  void snapshotsAndBars() throws Exception {
    String snap = json("/api/v1/snapshots/AAPL");
    assertThat((String) read(snap, "$.symbol")).isEqualTo("AAPL");
    assertThat((Object) read(snap, "$.minuteBar.t")).isNotNull();
    assertThat((String) read(snap, "$.dailyBar.t")).isEqualTo("2026-10-09T04:00:00.000Z");
    assertThat((Object) read(snap, "$.marketCap")).isNotNull();
    assertThat(code("/api/v1/snapshots/NOPE")).isEqualTo("SYMBOL_NOT_FOUND");

    String d1 = json("/api/v1/bars/AAPL?range=1D");
    assertThat((String) read(d1, "$.timeframe")).isEqualTo("1Min");
    assertThat((String) read(d1, "$.adjustment")).isEqualTo("SPLIT");
    assertThat((String) read(d1, "$.bars[0].t")).isEqualTo("2026-10-09T08:00:00.000Z");
    assertThat((String) read(d1, "$.bars[0].o")).matches("\\d+\\.\\d{4}");
    assertThat((Object) read(d1, "$.tailFrom")).isNull();
    String daily = json("/api/v1/bars/AAPL?timeframe=1day&limit=5&adjustment=raw");
    assertThat((List<Object>) read(daily, "$.bars")).hasSize(5);
    assertThat((String) read(daily, "$.adjustment")).isEqualTo("RAW");
    assertThat((String) read(json("/api/v1/bars/AAPL?range=5D"), "$.timeframe")).isEqualTo("5Min");
    assertThat((String) read(json("/api/v1/bars/AAPL?range=ALL"), "$.timeframe")).isEqualTo("1Month");
    assertThat(code("/api/v1/bars/AAPL?range=1D&start=2026-10-01T00:00:00Z")).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/bars/AAPL?timeframe=1Min&limit=0")).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/bars/AAPL?range=2D")).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/bars/NOPE?range=1D")).isEqualTo("SYMBOL_NOT_FOUND");
  }

  /** §15.10 Time &amp; Sales: the SIM's prints, newest first, each with a side; validation and unknown symbols. */
  @Test
  void timeAndSales() throws Exception {
    String t = json("/api/v1/assets/aapl/trades?limit=25");
    assertThat((String) read(t, "$.symbol")).isEqualTo("AAPL");
    assertThat((String) read(t, "$.source")).isEqualTo("SIMULATED");
    assertThat((Boolean) read(t, "$.streaming")).isTrue();
    List<Map<String, Object>> prints = read(t, "$.prints");
    assertThat(prints).hasSize(25).allSatisfy(p -> {
      assertThat((String) p.get("price")).matches("\\d+\\.\\d+");
      assertThat(p.get("size")).isInstanceOf(Integer.class);
      assertThat((String) p.get("side")).isIn("AT_ASK", "AT_BID", "BETWEEN");
      assertThat((String) p.get("t")).startsWith("2026-10-09T1");
      assertThat(p.get("id")).isNotNull();
    });
    assertThat((String) prints.get(0).get("t")).isGreaterThanOrEqualTo((String) prints.get(24).get("t"));
    assertThat((List<Object>) read(json("/api/v1/assets/AAPL/trades"), "$.prints")).hasSize(60);
    assertThat(code("/api/v1/assets/AAPL/trades?limit=201")).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/assets/NOPE/trades")).isEqualTo("SYMBOL_NOT_FOUND");
  }

  /** §15.10 analyst recommendation trends: the SIM's simulated months for a stock, none for an ETF. */
  @Test
  void recommendations() throws Exception {
    String r = json("/api/v1/assets/AAPL/recommendations");
    assertThat((String) read(r, "$.source")).isEqualTo("simulated");
    assertThat((List<Object>) read(r, "$.periods[*].period")).containsExactly("2026-10-01", "2026-09-01",
        "2026-08-01", "2026-07-01");
    Map<String, Object> first = read(r, "$.periods[0]");
    int sum = (Integer) first.get("strongBuy") + (Integer) first.get("buy") + (Integer) first.get("hold")
        + (Integer) first.get("sell") + (Integer) first.get("strongSell");
    assertThat(first.get("total")).isEqualTo(sum);
    String etf = json("/api/v1/assets/SPY/recommendations");
    assertThat((String) read(etf, "$.source")).isEqualTo("none");
    assertThat((List<Object>) read(etf, "$.periods")).isEmpty();
    assertThat((Object) read(etf, "$.asOf")).isNull();
    assertThat(code("/api/v1/assets/NOPE/recommendations")).isEqualTo("SYMBOL_NOT_FOUND");
  }

  /**
   * §15.10 screener on the SIM: presets, the statistics the first screen computes in the background (market caps,
   * relative volume), cursor paging, filters and validation.
   */
  @Test
  void screener() throws Exception {
    String g = json("/api/v1/market/screener?preset=top_gainers&limit=5");
    assertThat((String) read(g, "$.preset")).isEqualTo("TOP_GAINERS");
    assertThat((String) read(g, "$.sort")).isEqualTo("CHANGE_PCT");
    assertThat((String) read(g, "$.order")).isEqualTo("DESC");
    assertThat((Integer) read(g, "$.coverage.universe")).isGreaterThan(600);
    assertThat((Integer) read(g, "$.coverage.priced")).isGreaterThan(600);
    List<Map<String, Object>> gainers = read(g, "$.items");
    assertThat(gainers).hasSize(5).allSatisfy(i -> {
      assertThat(new java.math.BigDecimal((String) i.get("changePct"))).isPositive();
      assertThat((String) i.get("type")).isIn("COMMON_STOCK", "ADR");
      assertThat((String) i.get("quality")).isEqualTo("SIMULATED");
      assertThat((String) i.get("price")).matches("\\d+\\.\\d+");
    });
    // the SIM computes the statistics on the first screen, in the background
    Await.until(java.time.Duration.ofSeconds(60),
        () -> (Integer) read(jsonQuietly("/api/v1/market/screener?limit=1"), "$.coverage.withMarketCap") > 500,
        "the SIM's screener statistics");
    String large = json("/api/v1/market/screener?preset=LARGE_CAPS_UNDER_50&limit=100");
    List<Map<String, Object>> caps = read(large, "$.items");
    assertThat(caps).isNotEmpty().allSatisfy(i -> {
      assertThat(new java.math.BigDecimal((String) i.get("marketCap"))).isGreaterThanOrEqualTo(
          new java.math.BigDecimal("10000000000"));
      assertThat(new java.math.BigDecimal((String) i.get("price"))).isLessThanOrEqualTo(java.math.BigDecimal.valueOf(50));
      assertThat(i.get("sector")).isNotNull();
      assertThat(i.get("avgVolume")).isNotNull();
      assertThat((String) i.get("relVolume")).matches("\\d+\\.\\d{2}");
    });
    // cursor paging by symbol
    String p1 = json("/api/v1/market/screener?sort=symbol&limit=10");
    String cursor = read(p1, "$.nextCursor");
    assertThat(cursor).isNotBlank();
    List<String> first = read(p1, "$.items[*].symbol");
    List<String> second = read(json("/api/v1/market/screener?sort=SYMBOL&limit=10&cursor=" + cursor), "$.items[*].symbol");
    assertThat(second).hasSize(10);
    assertThat(second.get(0)).isGreaterThan(first.get(9));
    // filters
    List<String> sectors = read(json("/api/v1/market/screener?sectors=information_technology,FINANCIALS&limit=100"),
        "$.items[*].sector");
    assertThat(sectors).isNotEmpty().allSatisfy(x -> assertThat(x).isIn("INFORMATION_TECHNOLOGY", "FINANCIALS"));
    List<String> etfs = read(json("/api/v1/market/screener?types=ETF&limit=100"), "$.items[*].type");
    assertThat(etfs).isNotEmpty().containsOnly("ETF");
    assertThat(code("/api/v1/market/screener?minPrice=10&maxPrice=5")).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/market/screener?limit=101")).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/market/screener?cursor=bogus")).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/market/screener?types=OTHER")).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/market/screener?sort=BOGUS")).isEqualTo("VALIDATION_FAILED");
    assertThat(code("/api/v1/market/screener?minPrice=abc")).isEqualTo("VALIDATION_FAILED");
  }

  String jsonQuietly(String url) {
    try {
      return json(url);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void marketReadScopeIsRequired() throws Exception {
    Fixtures fx = new Fixtures(jdbc, time);
    String accountOnly = fx.apiKey(fx.human("acct" + SEQ.incrementAndGet()), Set.of(Scopes.ACCOUNT_READ),
        properties.security().apiKeyPepper());
    MvcResult r = mvc.perform(get("/api/v1/quotes?symbols=AAPL").header("Authorization", "Bearer " + accountOnly))
        .andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(403);
    assertThat((String) read(WebSession.contentOf(r), "$.code")).isEqualTo("FORBIDDEN_SCOPE");
    assertThat(mvc.perform(get("/api/v1/market/clock")).andReturn().getResponse().getStatus()).isEqualTo(401);
  }
}
