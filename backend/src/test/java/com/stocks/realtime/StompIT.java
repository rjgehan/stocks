package com.stocks.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.TestTimes;
import java.lang.reflect.Type;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * STOMP over native WebSocket against the real server (§19, §17.1 chain 3, §26 "STOMP CONNECT with the raw
 * XSRF-TOKEN succeeds and without it fails"): a browser-like login, the handshake with the session cookie, CONNECT
 * with the raw cookie token, heartbeats, the quote topic (Boot's JSON: decimals as strings), user queues that reach
 * only their owner, and the destination rules.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=-1")
@ActiveProfiles("test")
@Import({TestClockConfig.class, StompIT.Doubles.class})
class StompIT {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @TestConfiguration(proxyBeanMethods = false)
  static class Doubles {
    @Bean
    RecordingMarketData testMarketData(MutableTimeSource time) {
      RecordingMarketData md = new RecordingMarketData(time);
      md.withAsset("AAPL").prevClose("AAPL", "225.00")
          .quote("AAPL", TestTimes.START, "227.39", 300, "227.41", 200)
          .print("AAPL", TestTimes.START, "227.40", 100);
      return md;
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired QuoteFanout fanout;
  @Autowired UserPush push;
  @Autowired SubscriptionTracker tracker;
  @Autowired MarketDataService marketData;
  @Autowired JsonMapper jsonMapper;

  ThreadPoolTaskScheduler heartbeats;
  WebSocketStompClient stomp;
  Fixtures fx;

  @BeforeEach
  void setUp() {
    fx = new Fixtures(jdbc, time);
    heartbeats = new ThreadPoolTaskScheduler();
    heartbeats.afterPropertiesSet();
    stomp = new WebSocketStompClient(new StandardWebSocketClient());
    stomp.setMessageConverter(new StringMessageConverter() {
      @Override
      protected boolean supportsMimeType(MessageHeaders headers) {
        return true;   // the payloads are application/json; keep them as text
      }
    });
    stomp.setTaskScheduler(heartbeats);
    stomp.setDefaultHeartbeat(new long[] {10_000, 10_000});
  }

  @AfterEach
  void tearDown() {
    heartbeats.shutdown();
  }

  // ------------------------------------------------------------------------------------------- tests

  @Test
  void connectWithTheRawCookieTokenGetsTenSecondHeartbeatsAndLiveQuotes() throws Exception {
    Browser alice = login("alice");
    Client c = connect(alice, alice.xsrf());
    assertThat(c.connected.getHeartbeat()).containsExactly(10_000, 10_000);
    assertThat(marketData).isInstanceOf(RecordingMarketData.class);

    c.subscribe("/topic/quote.AAPL");
    awaitSubscribers("/topic/quote.AAPL", 1);
    fanout.onMarketUpdate("AAPL");
    JsonNode quote = jsonMapper.readTree(c.next("/topic/quote.AAPL"));
    assertThat(quote.get("symbol").asString()).isEqualTo("AAPL");
    assertThat(quote.get("bid").isString()).as("decimals are strings (§6.3)").isTrue();
    assertThat(quote.get("bid").asString()).isEqualTo("227.39");
    assertThat(quote.has("extended")).as("nulls present").isTrue();
    assertThat(((RecordingMarketData) marketData).openLeases().values())
        .anyMatch(l -> l.symbol().equals("AAPL") && l.owner().startsWith("ws:"));
    c.session.disconnect();
  }

  @Test
  void connectWithoutOrWithAWrongCsrfTokenFails() throws Exception {
    Browser alice = login("alice");
    assertThat(connectError(alice, null)).contains("CSRF_INVALID");
    assertThat(connectError(alice, "not-the-token")).contains("CSRF_INVALID");
  }

  @Test
  void theHandshakeNeedsASession() {
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    CompletableFuture<StompSession> future = stomp.connectAsync(url(), headers, new StompHeaders(),
        new StompSessionHandlerAdapter() {});
    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
  }

  @Test
  void userQueuesReachOnlyTheirOwner() throws Exception {
    Browser alice = login("alice");
    Browser bob = login("bob");
    Client a = connect(alice, alice.xsrf());
    Client b = connect(bob, bob.xsrf());
    a.subscribe("/user/queue/notifications");
    b.subscribe("/user/queue/notifications");
    awaitSubscribers("/user/queue/notifications", 2);

    assertThat(push.toPlayer(alice.playerId, "/queue/notifications", Map.of("title", "for alice"))).isTrue();
    assertThat(jsonMapper.readTree(a.next("/user/queue/notifications")).get("title").asString())
        .isEqualTo("for alice");
    assertThat(b.poll("/user/queue/notifications", 500)).as("bob gets nothing").isNull();
  }

  @Test
  void userQueuesReachEverySessionOfTheOwner() throws Exception {
    // phone + desktop: Spring's ordered user-destination path fails a message resolved to two sessions, so UserPush
    // sends one message per session
    Fixtures.PlayerRef alice = fx.human("alice" + SEQ.incrementAndGet());
    Browser phone = loginAs(alice);
    Browser desktop = loginAs(alice);
    Client a = connect(phone, phone.xsrf());
    Client b = connect(desktop, desktop.xsrf());
    a.subscribe("/user/queue/orders");
    b.subscribe("/user/queue/orders");
    awaitSubscribers("/user/queue/orders", 2);
    assertThat(tracker.sessionsOf(alice.id())).hasSize(2);

    for (int i = 1; i <= 3; i++) {
      assertThat(push.toPlayer(alice.id(), "/queue/orders", Map.of("seq", i))).isTrue();
    }
    for (Client c : List.of(a, b)) {
      for (int i = 1; i <= 3; i++) {
        assertThat(jsonMapper.readTree(c.next("/user/queue/orders")).get("seq").asInt()).isEqualTo(i);
      }
    }
  }

  @Test
  void unknownDestinationsAndTheAdminQueueEndTheConnectionWithAnError() throws Exception {
    Browser alice = login("alice");
    Client c = connect(alice, alice.xsrf());
    c.subscribe("/user/queue/admin");
    assertThat(c.errors.poll(5, TimeUnit.SECONDS)).contains("admins");

    Client d = connect(alice, alice.xsrf());
    d.subscribe("/queue/orders-user" + c.sessionId());
    assertThat(d.errors.poll(5, TimeUnit.SECONDS)).contains("FORBIDDEN");
  }

  @Test
  void aSixthConnectionOfOnePlayerIsRefused() throws Exception {
    Browser alice = login("alice");
    for (int i = 0; i < SubscriptionTracker.MAX_SESSIONS_PER_PLAYER; i++) {
      connect(alice, alice.xsrf());
    }
    assertThat(connectError(alice, alice.xsrf())).contains("RATE_LIMITED");
  }

  // ------------------------------------------------------------------------------------------- helpers

  /** A browser that logged in: its cookies (session + XSRF-TOKEN). */
  record Browser(long playerId, CookieManager cookies) {
    String xsrf() {
      return cookie("XSRF-TOKEN");
    }

    String cookie(String name) {
      return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name))
          .map(HttpCookie::getValue).findFirst().orElse(null);
    }

    String cookieHeader() {
      return cookies.getCookieStore().getCookies().stream().map(c -> c.getName() + "=" + c.getValue())
          .collect(Collectors.joining("; "));
    }
  }

  Browser login(String prefix) throws Exception {
    return loginAs(fx.human(prefix + SEQ.incrementAndGet()));
  }

  /** A new browser session of {@code player} (a second device of the same player when called twice). */
  Browser loginAs(Fixtures.PlayerRef player) throws Exception {
    CookieManager cookies = new CookieManager();
    HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
    String base = "http://127.0.0.1:" + port;
    http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf")).GET().build(),
        HttpResponse.BodyHandlers.discarding());
    Browser b = new Browser(player.id(), cookies);
    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
        .header("Content-Type", "application/json").header("X-XSRF-TOKEN", b.xsrf())
        .POST(HttpRequest.BodyPublishers.ofString("{\"handle\":\"" + player.handle() + "\",\"password\":\""
            + Fixtures.PASSWORD + "\"}", StandardCharsets.UTF_8))
        .build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    if (b.xsrf() == null) {   // the login rotated the token: fetch the new one, as the SPA does before CONNECT
      http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf")).GET().build(),
          HttpResponse.BodyHandlers.discarding());
    }
    assertThat(b.cookie("stocks_sid")).isNotNull();
    assertThat(b.xsrf()).isNotNull();
    return b;
  }

  String url() {
    return "ws://127.0.0.1:" + port + "/ws";
  }

  /** A connected STOMP client and everything it received. */
  final class Client extends StompSessionHandlerAdapter {
    StompSession session;
    StompHeaders connected;
    final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
    final Map<String, BlockingQueue<String>> messages = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
      this.session = session;
      this.connected = connectedHeaders;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      errors.add(headers.getFirst("message") + " " + payload);
    }

    @Override
    public void handleTransportError(StompSession session, Throwable exception) {
      errors.add("transport: " + exception);
    }

    @Override
    public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload,
                                Throwable exception) {
      errors.add("exception in " + command + ": " + exception);
    }

    @Override
    public Type getPayloadType(StompHeaders headers) {
      return String.class;
    }

    void subscribe(String destination) {
      BlockingQueue<String> queue = messages.computeIfAbsent(destination, k -> new LinkedBlockingQueue<>());
      session.subscribe(destination, new StompFrameHandler() {
        @Override
        public Type getPayloadType(StompHeaders headers) {
          return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
          queue.add((String) payload);
        }
      });
    }

    String next(String destination) throws InterruptedException {
      String m = poll(destination, 5_000);
      assertThat(m).as("a message on " + destination).isNotNull();
      return m;
    }

    String poll(String destination, long millis) throws InterruptedException {
      return messages.computeIfAbsent(destination, k -> new LinkedBlockingQueue<>())
          .poll(millis, TimeUnit.MILLISECONDS);
    }

    String sessionId() {
      return session.getSessionId();
    }
  }

  Client connect(Browser browser, String csrfToken) throws Exception {
    WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
    handshake.add("Cookie", browser.cookieHeader());
    StompHeaders connectHeaders = new StompHeaders();
    if (csrfToken != null) {
      connectHeaders.add("X-XSRF-TOKEN", csrfToken);
    }
    Client client = new Client();
    client.session = stomp.connectAsync(url(), handshake, connectHeaders, client).get(5, TimeUnit.SECONDS);
    return client;
  }

  /**
   * CONNECTs and expects an ERROR frame instead of CONNECTED: returns the ERROR's {@code message} header (the
   * server then closes the socket, so the connect future fails).
   */
  String connectError(Browser browser, String csrfToken) throws Exception {
    WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
    handshake.add("Cookie", browser.cookieHeader());
    StompHeaders connectHeaders = new StompHeaders();
    if (csrfToken != null) {
      connectHeaders.add("X-XSRF-TOKEN", csrfToken);
    }
    Client client = new Client();
    CompletableFuture<StompSession> future = stomp.connectAsync(url(), handshake, connectHeaders, client);
    String error = client.errors.poll(5, TimeUnit.SECONDS);
    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).as("no CONNECTED").isInstanceOf(
        ExecutionException.class);
    assertThat(client.connected).isNull();
    return error;
  }

  void awaitSubscribers(String destination, int n) throws InterruptedException {
    for (int i = 0; i < 100 && tracker.subscribers(destination) < n; i++) {
      Thread.sleep(20);
    }
    assertThat(tracker.subscribers(destination)).isGreaterThanOrEqualTo(n);
  }
}
