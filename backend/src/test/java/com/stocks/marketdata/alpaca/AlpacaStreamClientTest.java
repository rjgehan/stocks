package com.stocks.marketdata.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.time.SystemTimeSource;
import com.stocks.contract.marketdata.model.BarEvent;
import com.stocks.contract.marketdata.model.MarketEvent;
import com.stocks.contract.marketdata.model.QuoteEvent;
import com.stocks.contract.marketdata.model.StatusEvent;
import com.stocks.contract.marketdata.model.TradeEvent;
import com.stocks.marketdata.core.Json;
import com.stocks.marketdata.core.WebSocketStream;
import com.stocks.marketdata.spi.ConnectionState;
import com.stocks.marketdata.testing.Await;
import com.stocks.marketdata.testing.FakeWebSocketServer;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The Alpaca stream protocol (§15.5, §15.6) against a fake server: greeting, auth within the deadline, subscription
 * diffs (unsubscribe before subscribe), message parsing with size normalization, and the 405/406/402 policies.
 */
class AlpacaStreamClientTest {

  static final WebSocketStream.Timings FAST = new WebSocketStream.Timings(Duration.ofMillis(100),
      Duration.ofMillis(400), Duration.ofMillis(1500), Duration.ofSeconds(2), Duration.ofSeconds(30),
      Duration.ofMillis(1200), Duration.ofSeconds(2));

  FakeWebSocketServer server;
  /** The fake's view of the subscription (what Alpaca would hold). */
  final Set<String> trades = new TreeSet<>();
  final Set<String> quotes = new TreeSet<>();
  final Set<String> bars = new TreeSet<>();
  final List<MarketEvent> events = new CopyOnWriteArrayList<>();
  volatile String authReply = "[{\"T\":\"success\",\"msg\":\"authenticated\"}]";
  /** The fake's symbol limit: a subscribe that would exceed it is refused with 405 and changes nothing (docs). */
  volatile int symbolLimit = Integer.MAX_VALUE;
  AlpacaStreamClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new FakeWebSocketServer()
        .onOpen(c -> c.send("[{\"T\":\"success\",\"msg\":\"connected\"}]"))
        .onMessage((c, text) -> {
          JsonNode m = Json.parse(text);
          switch (Json.str(m, "action")) {
            case "auth" -> c.send(authReply);
            case "subscribe" -> {
              Set<String> after = new TreeSet<>(quotes);
              after.addAll(Json.strings(m, "quotes"));
              if (after.size() > symbolLimit) {
                c.send("[{\"T\":\"error\",\"code\":405,\"msg\":\"symbol limit exceeded\"}]");
                return;
              }
              trades.addAll(Json.strings(m, "trades"));
              quotes.addAll(Json.strings(m, "quotes"));
              bars.addAll(Json.strings(m, "bars"));
              c.send(subscription());
            }
            case "unsubscribe" -> {
              Json.strings(m, "trades").forEach(trades::remove);
              Json.strings(m, "quotes").forEach(quotes::remove);
              Json.strings(m, "bars").forEach(bars::remove);
              c.send(subscription());
            }
            default -> { }
          }
        });
  }

  String subscription() {
    return Json.write(List.of(java.util.Map.of("T", "subscription", "trades", List.copyOf(trades), "quotes",
        List.copyOf(quotes), "bars", List.copyOf(bars), "updatedBars", List.copyOf(bars), "statuses", List.of())));
  }

  AlpacaStreamClient start() {
    client = new AlpacaStreamClient(URI.create(server.url("/v2/iex")), "key-1", "secret-1", "iex",
        new SystemTimeSource(), FAST, () -> false, events::add);
    client.start();
    return client;
  }

  @AfterEach
  void tearDown() throws Exception {
    if (client != null) {
      client.close();
    }
    server.close();
  }

  @Test
  void authenticatesThenSubscribesTheDesiredSet() throws Exception {
    start();
    String auth = server.nextMessage(Duration.ofSeconds(5));
    JsonNode a = Json.parse(auth);
    assertThat(Json.str(a, "action")).isEqualTo("auth");
    assertThat(Json.str(a, "key")).isEqualTo("key-1");
    assertThat(Json.str(a, "secret")).isEqualTo("secret-1");
    Await.until(() -> client.state() == ConnectionState.LIVE, "LIVE");
    Instant before = Instant.now();
    client.setDesired(Set.of("AAPL", "MSFT"), Set.of("AAPL", "MSFT"), Set.of("AAPL", "MSFT"));
    Await.until(() -> client.subscribed().equals(Set.of("AAPL", "MSFT")), "subscribed");
    JsonNode sub = Json.parse(server.nextMessage(Duration.ofSeconds(5)));
    assertThat(Json.str(sub, "action")).isEqualTo("subscribe");
    assertThat(Json.strings(sub, "quotes")).containsExactly("AAPL", "MSFT");
    assertThat(Json.strings(sub, "updatedBars")).containsExactly("AAPL", "MSFT");
    assertThat(client.subscribedSince(Instant.now())).containsExactlyInAnyOrder("AAPL", "MSFT");
    assertThat(client.subscribedSince(before.minusSeconds(60))).isEmpty();
    // a new desired set: unsubscribe first, then subscribe
    client.setDesired(Set.of("AAPL", "NVDA"), Set.of("AAPL", "NVDA"), Set.of("AAPL", "NVDA"));
    JsonNode first = Json.parse(server.nextMessage(Duration.ofSeconds(5)));
    JsonNode second = Json.parse(server.nextMessage(Duration.ofSeconds(5)));
    assertThat(Json.str(first, "action")).isEqualTo("unsubscribe");
    assertThat(Json.strings(first, "quotes")).containsExactly("MSFT");
    assertThat(Json.str(second, "action")).isEqualTo("subscribe");
    assertThat(Json.strings(second, "quotes")).containsExactly("NVDA");
    Await.until(() -> client.subscribed().equals(Set.of("AAPL", "NVDA")), "re-subscribed");
  }

  @Test
  void parsesQuotesTradesBarsAndStatuses() throws Exception {
    start();
    Await.until(() -> client.state() == ConnectionState.LIVE, "LIVE");
    server.last().send("""
        [{"T":"q","S":"AAPL","bx":"V","bp":227.4,"bs":2,"ax":"V","ap":227.42,"as":3,"c":["R"],
          "t":"2026-10-09T14:30:00.123456789Z","z":"C"},
         {"T":"t","S":"AAPL","i":96921,"x":"V","p":227.41,"s":100,"c":["@"],"t":"2026-10-09T14:30:00.2Z","z":"C"},
         {"T":"b","S":"AAPL","o":227.3,"h":227.5,"l":227.2,"c":227.41,"v":4123,"t":"2026-10-09T14:29:00Z","n":31,
          "vw":227.38},
         {"T":"s","S":"AAPL","sc":"H","sm":"Trading Halt","rc":"T1","rm":"Halt News Pending",
          "t":"2026-10-09T14:31:00Z","z":"C"}]""");
    Await.until(() -> events.size() >= 4, "4 events");
    QuoteEvent q = (QuoteEvent) events.get(0);
    assertThat(q.quote().bid()).isEqualTo(new BigDecimal("227.4"));
    assertThat(q.quote().bidSize()).isEqualTo(200);                // round lots × 100 until the unit check says x1
    assertThat(q.quote().ts()).isEqualTo(Instant.parse("2026-10-09T14:30:00.123456Z"));
    TradeEvent t = (TradeEvent) events.get(1);
    assertThat(t.trade().eligible()).isTrue();
    assertThat(t.trade().consolidated()).isFalse();
    BarEvent b = (BarEvent) events.get(2);
    assertThat(b.bar().partialVolume()).isTrue();
    assertThat(b.updated()).isFalse();
    StatusEvent s = (StatusEvent) events.get(3);
    assertThat(s.halted()).isTrue();
    assertThat(s.reason()).contains("Halt News Pending");
    assertThat(client.rawQuoteSizes().median()).isEqualTo(3);
    client.setQuoteSizeMultiplier(1);
    server.last().send("[{\"T\":\"q\",\"S\":\"AAPL\",\"bp\":227.4,\"bs\":250,\"ap\":227.42,\"as\":300,"
        + "\"t\":\"2026-10-09T14:30:01Z\"}]");
    Await.until(() -> events.size() >= 5, "5 events");
    assertThat(((QuoteEvent) events.get(4)).quote().bidSize()).isEqualTo(250);
  }

  @Test
  void connectionLimitWaitsLongerBeforeReconnecting() {
    authReply = "[{\"T\":\"error\",\"code\":406,\"msg\":\"connection limit exceeded\"}]";
    start();
    Await.until(() -> client.state() == ConnectionState.BACKOFF, "BACKOFF after 406");
    assertThat(client.lastErrorCode()).isEqualTo(406);
    assertThat(client.detail()).contains("406");
    long t0 = System.nanoTime();
    authReply = "[{\"T\":\"success\",\"msg\":\"authenticated\"}]";
    Await.until(Duration.ofSeconds(5), () -> client.state() == ConnectionState.LIVE, "LIVE again");
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThan(Duration.ofMillis(1000));
    assertThat(server.connections()).hasSize(2);
  }

  /**
   * Review regression: after a 405 the client marked itself LIMITED but never re-sent, so nothing streamed until the
   * desired set changed (hours on a quiet evening), and the cap kept the alphabetically first symbols, dropping the
   * PINNED SPY benchmark. It now re-sends at once and keeps the first symbols in priority order.
   */
  @Test
  void after405TheCappedSetIsResentInPriorityOrder() {
    symbolLimit = 3;
    start();
    Await.until(() -> client.state() == ConnectionState.LIVE, "LIVE");
    Set<String> byPriority = new java.util.LinkedHashSet<>(List.of("SPY", "AAPL", "MSFT", "NVDA"));
    client.setDesired(byPriority, byPriority, byPriority);
    Await.until(Duration.ofSeconds(5), () -> client.subscribed().equals(Set.of("SPY", "AAPL", "MSFT")),
        "the capped set, re-sent without a change of the desired set");
    assertThat(client.state()).isEqualTo(ConnectionState.LIMITED);
    assertThat(client.symbolLimit()).isEqualTo(3);
    assertThat(quotes).containsExactlyInAnyOrder("SPY", "AAPL", "MSFT");
  }

  @Test
  void symbolLimitMarksTheStreamLimited() {
    start();
    Await.until(() -> client.state() == ConnectionState.LIVE, "LIVE");
    server.last().send("[{\"T\":\"error\",\"code\":405,\"msg\":\"symbol limit exceeded\"}]");
    Await.until(() -> client.state() == ConnectionState.LIMITED, "LIMITED");
    assertThat(client.detail()).contains("405");
  }

  @Test
  void reconnectsAfterADropAndResubscribes() {
    start();
    Await.until(() -> client.state() == ConnectionState.LIVE, "LIVE");
    client.setDesired(Set.of("SPY"), Set.of("SPY"), Set.of("SPY"));
    Await.until(() -> client.subscribed().contains("SPY"), "SPY");
    trades.clear();
    quotes.clear();
    bars.clear();
    server.last().drop();
    Await.until(() -> server.connections().size() == 2 && client.state() == ConnectionState.LIVE, "reconnected");
    Await.until(() -> client.subscribed().contains("SPY"), "SPY again");
    assertThat(quotes).containsExactly("SPY");
  }

  @Test
  void authFailureBacksOffWithoutSubscribing() {
    authReply = "[{\"T\":\"error\",\"code\":402,\"msg\":\"auth failed\"}]";
    start();
    Await.until(() -> client.state() == ConnectionState.BACKOFF, "BACKOFF after 402");
    assertThat(client.detail()).contains("402");
    assertThat(new ArrayList<>(server.drainMessages())).allSatisfy(m -> assertThat(m).contains("\"auth\""));
  }
}
