package com.stocks.marketdata.finnhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.AssetType;
import com.stocks.common.time.SystemTimeSource;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.contract.marketdata.model.MarketEvent;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.contract.marketdata.model.RecommendationTrend;
import com.stocks.marketdata.spi.CompanyProfile;
import com.stocks.contract.marketdata.model.TradeEvent;
import com.stocks.marketdata.config.MarketDataProperties;
import com.stocks.marketdata.core.Json;
import com.stocks.marketdata.core.ProviderHttp;
import com.stocks.marketdata.core.ProviderRateLimiter;
import com.stocks.marketdata.core.WebSocketStream;
import com.stocks.marketdata.spi.ConnectionState;
import com.stocks.marketdata.testing.Await;
import com.stocks.marketdata.testing.FakeHttpServer;
import com.stocks.marketdata.testing.FakeWebSocketServer;
import com.stocks.marketdata.testing.Providers;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Finnhub REST (profile2, metric, symbol types, search, company news) and the trade stream (§15.6). */
class FinnhubTest {

  @Test
  void restFixtures() {
    try (FakeHttpServer http = new FakeHttpServer()) {
      http.fixture("/stock/profile2", "finnhub/profile2.json").fixture("/stock/metric", "finnhub/metric.json")
          .fixture("/stock/symbol", "finnhub/symbol.json").fixture("/search", "finnhub/search.json")
          .fixture("/company-news", "finnhub/company-news.json");
      MarketDataProperties.Finnhub f = Providers.finnhub(http.url(), "ws://127.0.0.1:1");
      SystemTimeSource time = new SystemTimeSource();
      ProviderHttp ph = new ProviderHttp("finnhub", Duration.ofSeconds(2), Duration.ofSeconds(5),
          new ProviderRateLimiter("finnhub", 55, 30), time::now, Map.of("X-Finnhub-Token", f.token()));
      FinnhubRestClient rest = new FinnhubRestClient(f, ph);
      FinnhubProvider provider = new FinnhubProvider(f, rest, time, WebSocketStream.Timings.defaults());

      Fundamentals fu = provider.fundamentals("AAPL").orElseThrow();
      assertThat(fu.marketCap()).isEqualTo(new BigDecimal("3376000500000"));   // millions → dollars
      assertThat(fu.sharesOutstanding()).isEqualTo(new BigDecimal("14840390000"));
      assertThat(fu.peTtm()).isEqualTo(new BigDecimal("34.57"));
      assertThat(fu.epsTtm()).isEqualTo(new BigDecimal("6.58"));
      assertThat(fu.dividendYield()).isEqualByComparingTo("0.004398");          // percent → fraction
      assertThat(fu.week52High()).isEqualByComparingTo("260.1");
      assertThat(fu.beta()).isEqualByComparingTo("1.21");
      assertThat(fu.industry()).isEqualTo("Technology");
      assertThat(fu.website()).isEqualTo("https://www.apple.com/");
      assertThat(http.requests("/stock/profile2").getFirst().headers()).containsEntry("x-finnhub-token", "fh-token");
      assertThat(http.requests("/stock/metric").getFirst().query()).containsEntry("metric", "all");

      Map<String, AssetType> types = provider.assetTypes();
      assertThat(types).containsEntry("AAPL", AssetType.COMMON_STOCK).containsEntry("SPY", AssetType.ETF)
          .containsEntry("TSM", AssetType.ADR).containsEntry("REIT", AssetType.OTHER);
      assertThat(http.requests("/stock/symbol").getFirst().query()).containsEntry("exchange", "US");

      List<String[]> found = rest.search("apple", RefreshPriority.UI);
      assertThat(found).extracting(r -> r[0]).containsExactly("AAPL", "APLE");

      List<NewsItem> news = provider.news(Set.of("AAPL"), 5, Instant.parse("2026-10-10T00:00:00Z"));
      assertThat(news).hasSize(1);
      assertThat(news.getFirst().id()).isEqualTo("fh-7123");
      assertThat(news.getFirst().imageUrl()).isNull();
      assertThat(news.getFirst().symbols()).containsExactly("AAPL");
    }
  }

  /** §15.10: profile2 alone (the screener's one-call statistics) and the free recommendation trends. */
  @Test
  void profileAndRecommendationFixtures() {
    try (FakeHttpServer http = new FakeHttpServer()) {
      http.fixture("/stock/profile2", "finnhub/profile2.json")
          .fixture("/stock/recommendation", "finnhub/recommendation.json");
      MarketDataProperties.Finnhub f = Providers.finnhub(http.url(), "ws://127.0.0.1:1");
      SystemTimeSource time = new SystemTimeSource();
      ProviderHttp ph = new ProviderHttp("finnhub", Duration.ofSeconds(2), Duration.ofSeconds(5),
          new ProviderRateLimiter("finnhub", 55, 30), time::now, Map.of("X-Finnhub-Token", f.token()));
      FinnhubProvider provider = new FinnhubProvider(f, new FinnhubRestClient(f, ph), time,
          WebSocketStream.Timings.defaults());

      CompanyProfile p = provider.profile("AAPL", RefreshPriority.BACKGROUND).orElseThrow();
      assertThat(p.sharesOutstanding()).isEqualTo(new BigDecimal("14840390000"));
      assertThat(p.marketCap()).isEqualTo(new BigDecimal("3376000500000"));
      assertThat(p.industry()).isEqualTo("Technology");
      assertThat(p.source()).isEqualTo("finnhub");
      assertThat(http.requests("/stock/metric")).as("profile2 only: one call").isEmpty();

      List<RecommendationTrend> trends = provider.recommendations("AAPL");
      assertThat(trends).hasSize(4);
      assertThat(trends.getFirst()).isEqualTo(new RecommendationTrend("AAPL", java.time.LocalDate.of(2026, 10, 1), 13,
          24, 7, 0, 0));
      assertThat(trends.getFirst().total()).isEqualTo(44);
      assertThat(http.requests("/stock/recommendation").getFirst().query()).containsEntry("symbol", "AAPL");
    }
  }

  @Test
  void anEmptyProfileIsNoProfile() {
    try (FakeHttpServer http = new FakeHttpServer()) {
      http.json("/stock/profile2", "{}");
      MarketDataProperties.Finnhub f = Providers.finnhub(http.url(), "ws://127.0.0.1:1");
      SystemTimeSource time = new SystemTimeSource();
      ProviderHttp ph = new ProviderHttp("finnhub", Duration.ofSeconds(2), Duration.ofSeconds(5),
          new ProviderRateLimiter("finnhub", 55, 30), time::now, Map.of("X-Finnhub-Token", f.token()));
      assertThat(new FinnhubRestClient(f, ph).profile("SPY", RefreshPriority.BACKGROUND)).isEmpty();
    }
  }

  @Test
  void tradeStream() throws Exception {
    try (FakeWebSocketServer server = new FakeWebSocketServer()) {
      List<MarketEvent> events = new CopyOnWriteArrayList<>();
      WebSocketStream.Timings fast = new WebSocketStream.Timings(Duration.ofMillis(100), Duration.ofMillis(400),
          Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(1),
          Duration.ofSeconds(2));
      MarketDataProperties.Finnhub f = Providers.finnhub("http://127.0.0.1:1", server.url("/"));
      FinnhubProvider provider = new FinnhubProvider(f, null, new SystemTimeSource(), fast);
      FinnhubStreamClient client = provider.openStream(events::add, () -> false);
      try {
        Await.until(() -> client.state() == ConnectionState.LIVE, "LIVE");
        assertThat(server.last().path()).isEqualTo("/?token=fh-token");
        client.setDesired(Set.of("AAPL", "MSFT"), Set.of(), Set.of());
        JsonNode a = Json.parse(server.nextMessage(Duration.ofSeconds(5)));
        JsonNode b = Json.parse(server.nextMessage(Duration.ofSeconds(5)));
        assertThat(List.of(Json.str(a, "symbol"), Json.str(b, "symbol"))).containsExactly("AAPL", "MSFT");
        assertThat(Json.str(a, "type")).isEqualTo("subscribe");
        server.last().send("{\"type\":\"ping\"}");
        server.last().send("{\"data\":[{\"c\":[\"1\",\"12\"],\"p\":227.405,\"s\":\"AAPL\",\"t\":1791556200123,"
            + "\"v\":100}],\"type\":\"trade\"}");
        Await.until(() -> !events.isEmpty(), "trade");
        TradeEvent t = (TradeEvent) events.getFirst();
        assertThat(t.trade().price()).isEqualTo(new BigDecimal("227.405"));
        assertThat(t.trade().consolidated()).isTrue();
        assertThat(t.trade().ts()).isEqualTo(Instant.ofEpochMilli(1791556200123L));
        assertThat(t.trade().source()).isEqualTo("finnhub");
        client.setDesired(Set.of("AAPL"), Set.of(), Set.of());
        JsonNode u = Json.parse(server.nextMessage(Duration.ofSeconds(5)));
        assertThat(Json.str(u, "type")).isEqualTo("unsubscribe");
        assertThat(Json.str(u, "symbol")).isEqualTo("MSFT");
        assertThat(client.subscribed()).containsExactly("AAPL");
      } finally {
        client.close();
      }
    }
  }
}
