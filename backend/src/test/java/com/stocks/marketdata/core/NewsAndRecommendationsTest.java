package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.marketdata.Recommendations;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.contract.marketdata.model.RecommendationTrend;
import com.stocks.marketdata.spi.Capabilities;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** §15.10: symbol news merges the primary and Finnhub (deduplicated, cached); recommendation trends are cached. */
class NewsAndRecommendationsTest {

  static final Instant T = Instant.parse("2026-10-09T14:30:00Z");
  static final Capabilities CAPS = new Capabilities(false, false, false, 0, 60, Duration.ZERO, null, null, false, true,
      true, false, false, false, false, false);

  /** A provider whose news and recommendations come from suppliers, counting calls. */
  static final class Fake implements MarketDataProvider {
    final String id;
    final AtomicInteger newsCalls = new AtomicInteger();
    final AtomicInteger recommendationCalls = new AtomicInteger();
    Supplier<List<NewsItem>> news = List::of;
    Supplier<List<RecommendationTrend>> recommendations = List::of;

    Fake(String id) {
      this.id = id;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public Capabilities capabilities() {
      return CAPS;
    }

    @Override
    public List<NewsItem> news(Set<String> symbols, int limit, Instant before) {
      newsCalls.incrementAndGet();
      return news.get();
    }

    @Override
    public List<RecommendationTrend> recommendations(String symbol) {
      recommendationCalls.incrementAndGet();
      return recommendations.get();
    }
  }

  static NewsItem item(String id, String headline, String url, String source, Instant at) {
    return new NewsItem(id, headline, null, url, source, List.of("AAPL"), at, null);
  }

  @Test
  void symbolNewsMergesBothSourcesNewestFirstWithoutDuplicates() {
    Fake alpaca = new Fake("alpaca");
    Fake finnhub = new Fake("finnhub");
    alpaca.news = () -> List.of(item("a1", "Apple unveils new chips", "https://benzinga.com/1", "benzinga", T),
        item("a2", "Apple shares rise", "https://benzinga.com/2", "benzinga", T.minusSeconds(600)));
    finnhub.news = () -> List.of(item("f1", "Apple unveils new chips!", "https://yahoo.com/x", "Yahoo", T),
        item("f2", "Analysts weigh in on Apple", "https://marketwatch.com/y", "MarketWatch", T.minusSeconds(60)),
        item("f3", "Duplicate URL", "https://BENZINGA.com/2", "Yahoo", T.minusSeconds(30)));
    NewsService news = new NewsService(List.of(alpaca, finnhub), () -> true);

    List<NewsItem> out = news.news(Set.of("AAPL"), 10, null);
    assertThat(out).extracting(NewsItem::id).containsExactly("a1", "f2", "a2");
    assertThat(out).extracting(NewsItem::source).containsExactly("benzinga", "MarketWatch", "benzinga");
    assertThat(news.news(Set.of("AAPL"), 2, null)).extracting(NewsItem::id).containsExactly("a1", "f2");

    // cached: the primary for 60 s, Finnhub for 10 minutes
    news.news(Set.of("AAPL"), 10, null);
    assertThat(alpaca.newsCalls.get()).isEqualTo(2);           // limits 10 and 2 are different keys
    assertThat(finnhub.newsCalls.get()).isEqualTo(2);
  }

  @Test
  void generalNewsIsThePrimarysAndFinnhubOnlyFillsInWhenThePrimaryFails() {
    Fake alpaca = new Fake("alpaca");
    Fake finnhub = new Fake("finnhub");
    alpaca.news = () -> List.of(item("a1", "Stocks rally", "https://benzinga.com/1", "benzinga", T));
    finnhub.news = () -> List.of(item("f1", "Other", "https://yahoo.com/x", "Yahoo", T));
    NewsService news = new NewsService(List.of(alpaca, finnhub), () -> true);
    assertThat(news.news(Set.of(), 10, null)).extracting(NewsItem::id).containsExactly("a1");
    assertThat(finnhub.newsCalls.get()).as("company-news needs a symbol").isZero();
    // many symbols: the primary answers, Finnhub (one call per symbol) is not asked
    news.news(Set.of("AAPL", "MSFT", "NVDA", "AMD"), 10, null);
    assertThat(finnhub.newsCalls.get()).isZero();

    Fake down = new Fake("alpaca");
    down.news = () -> {
      throw new ProviderException("alpaca", 503, "down", null, null);
    };
    NewsService fallback = new NewsService(List.of(down, finnhub), () -> true);
    assertThat(fallback.news(Set.of("AAPL", "MSFT", "NVDA", "AMD"), 10, null)).extracting(NewsItem::id)
        .containsExactly("f1");
  }

  @Test
  void recommendationsAreCachedNewestFirstAndEmptyWithoutASource() {
    MutableTimeSource time = new MutableTimeSource(T);
    AtomicLong nanos = new AtomicLong(1);
    Fake finnhub = new Fake("finnhub");
    List<RecommendationTrend> trends = new ArrayList<>();
    for (int m = 1; m <= 9; m++) {
      trends.add(new RecommendationTrend("AAPL", LocalDate.of(2026, m, 1), 10, 20, 5, 1, 0));
    }
    trends.add(new RecommendationTrend("AAPL", LocalDate.of(2025, 12, 1), 0, 0, 0, 0, 0));   // no analysts: dropped
    finnhub.recommendations = () -> trends;
    RecommendationService service = new RecommendationService(finnhub, time, nanos::get);

    Recommendations r = service.recommendations("AAPL").orElseThrow();
    assertThat(r.source()).isEqualTo("finnhub");
    assertThat(r.fetchedAt()).isEqualTo(T);
    assertThat(r.trends()).hasSize(RecommendationService.MAX_PERIODS);
    assertThat(r.trends().getFirst().period()).isEqualTo(LocalDate.of(2026, 9, 1));
    assertThat(service.recommendations("AAPL")).contains(r);
    assertThat(finnhub.recommendationCalls.get()).isOne();
    nanos.addAndGet(RecommendationService.TTL.plusSeconds(1).toNanos());
    service.recommendations("AAPL");
    assertThat(finnhub.recommendationCalls.get()).as("refetched after 12 h").isEqualTo(2);

    // an ETF without analysts: one call, then the empty answer is cached too
    finnhub.recommendations = List::of;
    assertThat(service.recommendations("SPY")).isEmpty();
    assertThat(service.recommendations("SPY")).isEmpty();
    assertThat(finnhub.recommendationCalls.get()).isEqualTo(3);

    assertThat(new RecommendationService(null, time).recommendations("AAPL")).isEmpty();
  }

  @Test
  void aForbiddenRecommendationEndpointTurnsTheUpstreamOffForADay() {
    MutableTimeSource time = new MutableTimeSource(T);
    AtomicLong nanos = new AtomicLong(1);
    Fake finnhub = new Fake("finnhub");
    finnhub.recommendations = () -> {
      throw new ProviderException("finnhub", 403, "You don't have access to this resource.", null, null);
    };
    RecommendationService service = new RecommendationService(finnhub, time, nanos::get);
    assertThat(service.recommendations("AAPL")).isEmpty();
    assertThat(service.available()).isFalse();
    assertThat(service.recommendations("MSFT")).isEmpty();
    assertThat(finnhub.recommendationCalls.get()).as("no call while off").isOne();
    nanos.addAndGet(RecommendationService.FORBIDDEN_TTL.plusSeconds(1).toNanos());
    assertThat(service.available()).isTrue();
    finnhub.recommendations = List::of;
    assertThat(service.recommendations("MSFT")).isEmpty();
    assertThat(finnhub.recommendationCalls.get()).isEqualTo(2);
  }
}
