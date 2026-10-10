package com.stocks.marketdata.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.MoverList;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Movers;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Snapshot;
import com.stocks.contract.marketdata.model.TradingDayData;
import com.stocks.marketdata.config.MarketDataProperties;
import com.stocks.marketdata.core.AssetService;
import com.stocks.marketdata.core.ProviderHttp;
import com.stocks.marketdata.core.ProviderRateLimiter;
import com.stocks.marketdata.core.Universe;
import com.stocks.marketdata.core.WebSocketStream;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.marketdata.spi.ProviderException;
import com.stocks.marketdata.testing.FakeHttpServer;
import com.stocks.marketdata.testing.Providers;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Alpaca REST adapter against recorded fixtures shaped like the official docs (§15.6). */
class AlpacaRestClientTest {

  FakeHttpServer http;
  MutableTimeSource time;
  ProviderRateLimiter limiter;
  AlpacaMarketDataProvider alpaca;

  @BeforeEach
  void setUp() {
    http = new FakeHttpServer();
    time = TestTimes.clock();
    MarketDataProperties.Alpaca a = Providers.alpaca(http.url(), "ws://127.0.0.1:1/v2/iex");
    limiter = new ProviderRateLimiter("alpaca", 180, 0);
    ProviderHttp ph = new ProviderHttp("alpaca", a.connectTimeout(), a.readTimeout(), limiter, time::now,
        Map.of("APCA-API-KEY-ID", a.keyId(), "APCA-API-SECRET-KEY", a.secretKey()));
    alpaca = new AlpacaMarketDataProvider(Providers.hybrid(a, null), new AlpacaRestClient(a, ph, time::now), time,
        WebSocketStream.Timings.defaults());
  }

  @AfterEach
  void tearDown() {
    http.close();
  }

  @Test
  void snapshotsWithExplicitFeedAndKeys() {
    http.fixture("/v2/stocks/snapshots", "alpaca/snapshots.json");
    Map<String, Snapshot> snaps = alpaca.snapshots(Set.of("MSFT", "AAPL"), RefreshPriority.UI);
    FakeHttpServer.Request req = http.requests("/v2/stocks/snapshots").getFirst();
    assertThat(req.query()).containsEntry("symbols", "AAPL,MSFT").containsEntry("feed", "iex");
    assertThat(req.headers()).containsEntry("apca-api-key-id", "test-key").containsEntry("apca-api-secret-key",
        "test-secret");
    Snapshot aapl = snaps.get("AAPL");
    Quote q = aapl.quote();
    assertThat(q.bid()).isEqualTo(new BigDecimal("227.4"));
    assertThat(q.ask()).isEqualTo(new BigDecimal("227.42"));
    assertThat(q.bidSize()).isEqualTo(200);                      // REST sizes are shares
    assertThat(q.ts()).isEqualTo(Instant.parse("2026-10-09T14:29:59.052763Z"));   // ns truncated to µs
    assertThat(q.observedAt()).isEqualTo(time.now());
    assertThat(aapl.lastTrade().price()).isEqualTo(new BigDecimal("227.41"));
    assertThat(aapl.lastTrade().eligible()).isTrue();
    assertThat(aapl.lastTrade().consolidated()).isFalse();       // IEX is one venue
    assertThat(snaps.get("MSFT").lastTrade().eligible()).isFalse();   // condition I = odd lot
    assertThat(aapl.dailyBar().close()).isEqualTo(new BigDecimal("227.41"));
    assertThat(aapl.dailyBar().partialVolume()).isTrue();
    assertThat(aapl.prevDailyBar().start()).isEqualTo(Instant.parse("2026-10-08T04:00:00Z"));
    assertThat(aapl.minuteBar().tf()).isEqualTo(Timeframe.MIN1);
  }

  @Test
  void snapshotsAreBatched() {
    http.json("/v2/stocks/snapshots", "{}");
    alpaca.setSnapshotBatch(2);
    alpaca.snapshots(Set.of("A", "B", "C", "D", "E"), RefreshPriority.UI);
    assertThat(http.requests("/v2/stocks/snapshots")).extracting(r -> r.query().get("symbols"))
        .containsExactly("A,B", "C,D", "E");
  }

  @Test
  void latestQuotesAndTrades() {
    http.fixture("/v2/stocks/quotes/latest", "alpaca/quotes-latest.json")
        .fixture("/v2/stocks/trades/latest", "alpaca/trades-latest.json");
    assertThat(alpaca.latestQuotes(Set.of("AAPL")).get("AAPL").ask()).isEqualTo(new BigDecimal("227.43"));
    assertThat(alpaca.latestTrades(Set.of("AAPL")).get("AAPL").size()).isEqualTo(100);
    assertThat(http.requests("/v2/stocks/quotes/latest").getFirst().query()).containsEntry("feed", "iex");
  }

  @Test
  void barsFollowPageTokens() {
    http.route("/v2/stocks/bars", r -> FakeHttpServer.Response.json(FakeHttpServer.fixture(
        r.query().containsKey("page_token") ? "alpaca/bars-page2.json" : "alpaca/bars-page1.json")));
    Map<String, List<Bar>> bars = alpaca.bars(new BarQuery(Set.of("AAPL", "MSFT"), Timeframe.DAY1,
        Instant.parse("2026-10-01T04:00:00Z"), Instant.parse("2026-10-10T04:00:00Z"), AdjustmentMode.SPLIT, false, 1000,
        "sip"), RefreshPriority.UI);
    assertThat(bars.get("AAPL")).hasSize(2);
    assertThat(bars.get("MSFT")).hasSize(1);
    assertThat(bars.get("AAPL").getFirst().partialVolume()).isFalse();        // SIP
    List<FakeHttpServer.Request> reqs = http.requests("/v2/stocks/bars");
    assertThat(reqs).hasSize(2);
    assertThat(reqs.getFirst().query()).containsEntry("timeframe", "1Day").containsEntry("adjustment", "split")
        .containsEntry("feed", "sip").containsEntry("sort", "asc").containsEntry("start", "2026-10-01T04:00:00Z")
        .containsEntry("end", "2026-10-10T04:00:00Z");
    assertThat(reqs.get(1).query()).containsEntry("page_token", "QUFQTHxEfDIwMjYtMTAtMDk=");
  }

  @Test
  void assetsMergeWithTypesAndTiers() {
    http.fixture("/v2/assets", "alpaca/assets.json");
    List<Asset> listed = alpaca.assets();
    assertThat(http.requests("/v2/assets").getFirst().query()).containsEntry("status", "active")
        .containsEntry("asset_class", "us_equity");
    assertThat(listed).hasSize(6);
    List<Asset> merged = AssetService.merge(listed, Map.of("AAPL", AssetType.COMMON_STOCK, "SPY", AssetType.ETF),
        Map.of("SMLL", new BigDecimal("25000000")), Universe.load());
    assertThat(merged).extracting(Asset::symbol).containsExactly("AAPL", "SPY", "BRK.B", "SMLL");   // no OTC, tradable
    Asset spy = merged.get(1);
    assertThat(spy.type()).isEqualTo(AssetType.ETF);
    assertThat(spy.fractionable()).isTrue();
    assertThat(spy.tier()).isEqualTo(LiquidityTier.MEGA);              // universe tier when no ADV
    assertThat(merged.get(3).tier()).isEqualTo(LiquidityTier.MID);     // from the ADV
    assertThat(merged.get(3).fractionable()).isFalse();
  }

  @Test
  void calendarRows() {
    http.fixture("/v2/calendar", "alpaca/calendar.json");
    List<TradingDayData> days = alpaca.calendar(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31));
    assertThat(http.requests("/v2/calendar").getFirst().query()).containsEntry("start", "2026-10-01")
        .containsEntry("end", "2026-12-31");
    assertThat(days).hasSize(3);
    TradingDayData early = days.get(2);
    assertThat(early.preOpen()).isEqualTo(LocalTime.of(4, 0));
    assertThat(early.close()).isEqualTo(LocalTime.of(13, 0));
    assertThat(early.postClose()).isEqualTo(LocalTime.of(17, 0));
    assertThat(early.settlementDate()).isEqualTo(LocalDate.of(2026, 11, 30));
  }

  @Test
  void newsAndScreener() {
    http.fixture("/v1beta1/news", "alpaca/news.json")
        .fixture("/v1beta1/screener/stocks/movers", "alpaca/movers.json")
        .fixture("/v1beta1/screener/stocks/most-actives", "alpaca/most-actives.json");
    List<NewsItem> news = alpaca.news(Set.of("AAPL"), 5, null);
    assertThat(news).hasSize(2);
    assertThat(news.getFirst().imageUrl()).isEqualTo("https://cdn.benzinga.com/thumb.jpg");
    assertThat(news.getFirst().publishedAt()).isEqualTo(Instant.parse("2026-10-09T13:58:10Z"));
    assertThat(news.get(1).summary()).isNull();
    assertThat(http.requests("/v1beta1/news").getFirst().query()).containsEntry("symbols", "AAPL")
        .containsEntry("sort", "desc");
    Movers gainers = alpaca.movers(MoverList.GAINERS, 10);
    assertThat(gainers.items().getFirst().changePct()).isEqualByComparingTo("0.0325");
    assertThat(alpaca.movers(MoverList.LOSERS, 10).items()).extracting(m -> m.symbol()).containsExactly("XYZ");
    assertThat(alpaca.movers(MoverList.ACTIVES, 10).items().getFirst().volume()).isEqualTo(123456789L);
  }

  @Test
  void http429BlocksTheBudgetUntilReset() {
    long reset = time.now().getEpochSecond() + 30;
    http.status("/v2/stocks/snapshots", 429, "{\"message\":\"too many requests.\"}",
        Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", String.valueOf(reset)));
    assertThatThrownBy(() -> alpaca.snapshots(Set.of("AAPL"), RefreshPriority.UI))
        .isInstanceOfSatisfying(ProviderException.class, e -> {
          assertThat(e.status()).isEqualTo(429);
          assertThat(e.retryAt()).isEqualTo(Instant.ofEpochSecond(reset));
        });
    assertThat(limiter.blocked()).isTrue();
    // the next call is refused locally, without touching the network
    assertThatThrownBy(() -> alpaca.snapshots(Set.of("AAPL"), RefreshPriority.UI))
        .isInstanceOf(ProviderException.class);
    assertThat(http.requests("/v2/stocks/snapshots")).hasSize(1);
  }

  @Test
  void errorsBecomeProviderExceptions() {
    http.status("/v2/stocks/snapshots", 403, "{\"message\":\"forbidden\"}", Map.of());
    assertThatThrownBy(() -> alpaca.snapshots(Set.of("AAPL"), RefreshPriority.UI))
        .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.status()).isEqualTo(403));
    MarketDataProperties.Alpaca down = Providers.alpaca("http://127.0.0.1:1", "ws://127.0.0.1:1/v2/iex");
    ProviderHttp ph = new ProviderHttp("alpaca", down.connectTimeout(), down.readTimeout(),
        new ProviderRateLimiter("alpaca", 180, 0), time::now, Map.of());
    AlpacaRestClient unreachable = new AlpacaRestClient(down, ph, time::now);
    assertThatThrownBy(() -> unreachable.snapshots(Set.of("AAPL"), "iex", RefreshPriority.UI))
        .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.status()).isZero());
    assertThat(ph.lastError()).contains("/v2/stocks/snapshots");
  }

  @Test
  void logosAreBytes() {
    http.route("/v1beta1/logos/AAPL", r -> new FakeHttpServer.Response(200, "\u0089PNG....", Map.of()));
    assertThat(new String(alpaca.rest().logo("AAPL", RefreshPriority.UI), java.nio.charset.StandardCharsets.UTF_8))
        .contains("PNG");
    assertThat(http.requests("/v1beta1/logos/AAPL").getFirst().query()).containsEntry("placeholder", "true");
  }
}
