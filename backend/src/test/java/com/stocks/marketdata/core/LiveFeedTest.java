package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.calendar.InMemoryCalendars;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.SessionType;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.contract.marketdata.MarketUpdateListener;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.marketdata.alpaca.AlpacaMarketDataProvider;
import com.stocks.marketdata.alpaca.AlpacaRestClient;
import com.stocks.marketdata.config.ExecQuoteRules;
import com.stocks.marketdata.config.MarketDataProperties;
import com.stocks.marketdata.testing.FakeHttpServer;
import com.stocks.marketdata.testing.Providers;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The live feed without streams (§15.5, §15.9): REST snapshots land in the quote book, quotes are labelled by
 * confirmation age, the executable quote honors the poll completion time (§12.3 polled symbols), the poller picks
 * symbols by lease class, and an unreachable provider degrades to NONE/STALE with health DOWN.
 */
class LiveFeedTest {

  static final Instant NOW = Instant.parse("2026-10-09T14:30:00Z");               // Fri 10:30 ET, REGULAR
  final MutableTimeSource time = new MutableTimeSource(NOW);
  final MarketCalendar calendar = InMemoryCalendars.bundled();
  final List<String> notified = new CopyOnWriteArrayList<>();
  FakeHttpServer http;

  LiveFeed feed(String url) {
    MarketDataProperties.Alpaca a = Providers.alpaca(url, "ws://127.0.0.1:1/v2/iex");
    MarketDataProperties props = Providers.hybrid(a, null);
    ProviderHttp ph = new ProviderHttp("alpaca", Duration.ofSeconds(1), Duration.ofSeconds(2),
        new ProviderRateLimiter("alpaca", 180, 0), time::now, Map.of());
    AlpacaMarketDataProvider alpaca = new AlpacaMarketDataProvider(props, new AlpacaRestClient(a, ph, time::now), time,
        WebSocketStream.Timings.defaults());
    MarketUpdateListener l = notified::add;
    QuoteBook book = new QuoteBook(time, () -> List.of(l));
    return new LiveFeed(props, alpaca, ph, null, null, null, book, new SubscriptionBudgetManager(time), calendar, time,
        ExecQuoteRules.defaults(), s -> LiquidityTier.MEGA, List::of);
  }

  @AfterEach
  void tearDown() {
    if (http != null) {
      http.close();
    }
  }

  /**
   * Review regression (§12.5): every snapshot poll re-applied the same latestTrade and buffered it as a new print, so
   * one upstream IEX print could fill a resting limit several times over. A trade is one print however often it is
   * seen; a new trade is still buffered.
   */
  @Test
  void aTradeSeenAgainOnEverySnapshotPollIsOnePrint() {
    QuoteBook book = new QuoteBook(time, List::of);
    com.stocks.contract.marketdata.model.Trade trade = new com.stocks.contract.marketdata.model.Trade("AAPL",
        new BigDecimal("227.41"), 100, NOW, NOW, "V", List.of(), false, true, "52983525029461", "alpaca");
    com.stocks.contract.marketdata.model.Snapshot snap = new com.stocks.contract.marketdata.model.Snapshot("AAPL",
        trade, null, null, null, null, NOW);
    int prints = 0;
    for (int i = 0; i < 5; i++) {
      time.advance(Duration.ofSeconds(2));
      book.applySnapshot(snap, time.now(), null, null);
      prints += book.drainPrints("AAPL").size();
    }
    assertThat(prints).isEqualTo(1);
    com.stocks.contract.marketdata.model.Trade next = new com.stocks.contract.marketdata.model.Trade("AAPL",
        new BigDecimal("227.42"), 50, NOW.plusSeconds(11), NOW.plusSeconds(11), "V", List.of(), false, true,
        "52983525029462", "alpaca");
    book.applySnapshot(new com.stocks.contract.marketdata.model.Snapshot("AAPL", next, null, null, null, null,
        time.now()), time.now(), null, null);
    assertThat(book.drainPrints("AAPL")).hasSize(1);
  }

  @Test
  void snapshotsBecomeQuotesLabelledByConfirmationAge() {
    http = new FakeHttpServer().fixture("/v2/stocks/snapshots", "alpaca/snapshots.json");
    LiveFeed feed = feed(http.url());
    Map<String, QuoteView> quotes = feed.quotes(List.of("AAPL", "MSFT"));
    QuoteView aapl = quotes.get("AAPL");
    assertThat(aapl.quality()).isEqualTo(DataQuality.REALTIME_SINGLE_VENUE);
    assertThat(aapl.bid()).isEqualByComparingTo("227.40");
    assertThat(aapl.last()).isEqualByComparingTo("227.41");
    assertThat(aapl.prevClose()).isEqualByComparingTo("225.00");
    assertThat(aapl.change()).isEqualByComparingTo("2.41");
    assertThat(aapl.changePct()).isEqualByComparingTo("0.0107111111");
    assertThat(aapl.dayOpen()).isEqualByComparingTo("225.5");
    assertThat(aapl.volumeIsPartial()).isTrue();
    assertThat(aapl.source()).isEqualTo("alpaca");
    assertThat(notified).contains("AAPL", "MSFT");
    // a second read within 10 s is served from the book
    feed.quotes(List.of("AAPL"));
    assertThat(http.requests("/v2/stocks/snapshots")).hasSize(1);
    // 20 s later the quote is no longer confirmed: STALE during venue hours (peek never calls the provider)
    time.advance(Duration.ofSeconds(20));
    assertThat(feed.peek(List.of("AAPL")).get("AAPL").quality()).isEqualTo(DataQuality.STALE);
    assertThat(feed.health()).isEqualTo(com.stocks.marketdata.core.MarketFeed.Health.DEGRADED);  // REST, no stream
  }

  @Test
  void executableQuoteNeedsAPollCompletedAfterEligibility() {
    http = new FakeHttpServer().fixture("/v2/stocks/snapshots", "alpaca/snapshots.json");
    LiveFeed feed = feed(http.url());
    feed.fetch(Set.of("AAPL"), RefreshPriority.ENGINE);
    time.advance(Duration.ofSeconds(1));
    Instant eligible = time.now();
    assertThat(feed.executableQuote("AAPL", eligible, SessionType.REGULAR)).isEmpty();     // look-ahead guard
    time.advance(Duration.ofMillis(300));
    feed.fetch(Set.of("AAPL"), RefreshPriority.ORDER_CRITICAL);
    ExecQuote q = feed.executableQuote("AAPL", eligible, SessionType.REGULAR).orElseThrow();
    assertThat(q.quality()).isEqualTo(DataQuality.REALTIME_SINGLE_VENUE);
    assertThat(q.confirmedAt()).isEqualTo(time.now());
    assertThat(q.ask()).isEqualByComparingTo("227.42");
    // the exchange timestamp is old (more than 5 min) once time moves on → no executable quote, even if polled
    time.advance(Duration.ofMinutes(6));
    feed.fetch(Set.of("AAPL"), RefreshPriority.ORDER_CRITICAL);
    assertThat(feed.executableQuote("AAPL", eligible, SessionType.REGULAR)).isEmpty();
    // prints from the IEX snapshot are drained once, ineligible ones never
    assertThat(feed.drainPrints("AAPL")).extracting(p -> p.price()).contains(new BigDecimal("227.41"));
    assertThat(feed.drainPrints("AAPL")).isEmpty();
  }

  @Test
  void pollerPicksSymbolsByLeaseClass() {
    http = new FakeHttpServer().json("/v2/stocks/snapshots", "{}");
    MarketDataProperties.Alpaca a = Providers.alpaca(http.url(), "ws://127.0.0.1:1/v2/iex");
    LiveFeed feed = feed(http.url());
    // reach the feed's budget through a lease on the same manager: rebuild with a shared budget
    SubscriptionBudgetManager budget = new SubscriptionBudgetManager(time);
    ProviderHttp ph = new ProviderHttp("alpaca", Duration.ofSeconds(1), Duration.ofSeconds(2),
        new ProviderRateLimiter("alpaca", 180, 0), time::now, Map.of());
    MarketDataProperties props = Providers.hybrid(a, null);
    LiveFeed polled = new LiveFeed(props, new AlpacaMarketDataProvider(props, new AlpacaRestClient(a, ph, time::now),
        time, WebSocketStream.Timings.defaults()), ph, null, null, null, new QuoteBook(time, List::of), budget,
        calendar, time, ExecQuoteRules.defaults(), s -> LiquidityTier.MEGA, () -> List.of("KO"));
    budget.lease("ORD", LeasePriority.ORDER_RESTING, "order:1");
    budget.lease("VIEW", LeasePriority.VIEWING, "ws:1");
    budget.lease("HOLD", LeasePriority.HOLDING, "hold:HOLD");
    polled.poll();
    List<String> first = http.requests("/v2/stocks/snapshots").stream().map(r -> r.query().get("symbols")).toList();
    assertThat(first).containsExactly("ORD", "HOLD,VIEW", "KO");          // engine, UI (viewing + warm), background
    time.advance(Duration.ofSeconds(3));
    polled.poll();
    List<String> second = http.requests("/v2/stocks/snapshots").stream().map(r -> r.query().get("symbols")).toList();
    assertThat(second.subList(3, second.size())).containsExactly("ORD", "VIEW");   // hot every 2 s; warm every 10 s
    assertThat(feed).isNotNull();
  }

  @Test
  void unreachableProviderDegradesWithoutFailing() {
    LiveFeed feed = feed("http://127.0.0.1:1");
    QuoteView q = feed.quote("AAPL");
    assertThat(q.quality()).isEqualTo(DataQuality.NONE);
    assertThat(q.last()).isNull();
    assertThat(feed.health()).isEqualTo(com.stocks.marketdata.core.MarketFeed.Health.DOWN);
    assertThat(feed.providers().getFirst().state()).isEqualTo("DOWN");
    assertThat(feed.providers().getFirst().probe()).containsKey("restLastError");
    assertThat(feed.executableQuote("AAPL", NOW, SessionType.REGULAR)).isEmpty();
  }

  /** §29: on a Saturday a crypto pair is live, consolidated and executable while the stock market is closed. */
  @Test
  void aCryptoPairTradesOnASaturday() {
    time.set(Instant.parse("2026-10-10T15:00:05Z"));                          // Sat 11:00 ET: stocks CLOSED
    http = new FakeHttpServer().json("/v1beta3/crypto/us/snapshots", """
        {"snapshots": {"BTC/USD": {
          "latestTrade": {"t": "2026-10-10T15:00:01Z", "p": 112005.12, "s": 0.01, "i": 1},
          "latestQuote": {"t": "2026-10-10T15:00:02Z", "bp": 112000.004, "bs": 0.5, "ap": 112010.996, "as": 0.7},
          "dailyBar": {"t": "2026-10-10T00:00:00Z", "o": 111500, "h": 112500, "l": 111000, "c": 112005.12, "v": 10},
          "prevDailyBar": {"t": "2026-10-09T00:00:00Z", "o": 110000, "h": 111600, "l": 109000, "c": 111000, "v": 12}
        }}}""");
    LiveFeed feed = feed(http.url());
    QuoteView btc = feed.quotes(List.of("BTC/USD")).get("BTC/USD");
    assertThat(btc.quality()).isEqualTo(DataQuality.REALTIME_CONSOLIDATED);
    assertThat(btc.bid()).isEqualByComparingTo("112000.00");
    assertThat(btc.ask()).isEqualByComparingTo("112011.00");
    assertThat(btc.last()).isEqualByComparingTo("112005.12");
    assertThat(btc.prevClose()).isEqualByComparingTo("111000.00");             // yesterday (ET), 24:00
    assertThat(btc.extended()).isNull();
    assertThat(http.requests("/v2/stocks/snapshots")).isEmpty();
    assertThat(feed.executableQuote("BTC/USD", time.now().minusSeconds(10), com.stocks.common.domain.SessionType.REGULAR))
        .isPresent();
    // 90 s later (no fresh quote nor print) a pair is STALE even on a weekend: crypto has no dark hours
    time.advance(Duration.ofSeconds(90));
    assertThat(feed.peek(List.of("BTC/USD")).get("BTC/USD").quality()).isEqualTo(DataQuality.STALE);
  }
}
