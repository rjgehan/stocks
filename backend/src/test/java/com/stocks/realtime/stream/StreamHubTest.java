package com.stocks.realtime.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.domain.SessionType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.PlayerStatusChanged;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.JacksonConfig;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.portfolio.LeaderboardService;
import com.stocks.contract.realtime.dto.StreamTopic;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.realtime.MarketSessionTopic;
import com.stocks.realtime.QuoteMessages;
import com.stocks.realtime.RecordingMarketData;
import com.stocks.realtime.StompDestinations;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestPrincipals;
import com.stocks.testkit.TestTimes;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * §19.3 the SSE hub without HTTP: frames in order ({@code retry}, {@code hello}, replays, live), resumable ids and
 * the ring buffer ({@code reset} RESTARTED / EXPIRED / UNKNOWN), the reconnect window, isolation between players on
 * live events and replays, caps, topics, quote coalescing and leases, heartbeats, max age, revocation and slow
 * consumers.
 */
class StreamHubTest {

  static final JsonMapper JSON = JacksonConfig.newMapper();
  static final Instant NOW = TestTimes.START;

  /** Records frames; {@code gate} (when set) holds the first send until released. */
  static final class Sink implements StreamSink {
    final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    volatile boolean closed;
    volatile CountDownLatch gate;

    @Override
    public void send(String frame) throws IOException {
      CountDownLatch g = gate;
      if (g != null) {
        try {
          g.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      frames.add(frame);
    }

    @Override
    public void close() {
      closed = true;
    }

    /** The next frame that is not a heartbeat comment, or null after 3 s. */
    Frame next() throws InterruptedException {
      while (true) {
        String f = frames.poll(3, TimeUnit.SECONDS);
        if (f == null) {
          return null;
        }
        if (!f.startsWith(":")) {
          return Frame.parse(f);
        }
      }
    }

    /** Frames until {@code event} (inclusive). */
    List<Frame> until(String event) throws InterruptedException {
      List<Frame> out = new ArrayList<>();
      Frame f;
      while ((f = next()) != null) {
        out.add(f);
        if (event.equals(f.event())) {
          return out;
        }
      }
      throw new AssertionError("no '" + event + "' frame; got " + out);
    }

    /** No frame (other than heartbeats) arrives within {@code millis}. */
    boolean quietFor(long millis) throws InterruptedException {
      long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
      while (System.nanoTime() < end) {
        String f = frames.poll(50, TimeUnit.MILLISECONDS);
        if (f != null && !f.startsWith(":")) {
          return false;
        }
      }
      return true;
    }
  }

  record Frame(String id, String event, String data, String raw) {
    static Frame parse(String raw) {
      String id = null;
      String event = null;
      StringBuilder data = new StringBuilder();
      for (String line : raw.split("\n")) {
        if (line.startsWith("id: ")) {
          id = line.substring(4);
        } else if (line.startsWith("event: ")) {
          event = line.substring(7);
        } else if (line.startsWith("data: ")) {
          data.append(line.substring(6));
        } else if (line.startsWith("retry: ")) {
          event = "retry";
        }
      }
      return new Frame(id, event, data.toString(), raw);
    }

    JsonNode json() {
      return JSON.readTree(data);
    }
  }

  MutableTimeSource time;
  RecordingMarketData market;
  PlayerQueries players;
  TestDb db;
  JdbcClient jdbc;
  StreamHub hub;
  final List<StreamConnection> opened = new ArrayList<>();

  static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
    DefaultListableBeanFactory f = new DefaultListableBeanFactory();
    if (bean != null) {
      f.registerSingleton("bean", bean);
    }
    return f.getBeanProvider(type);
  }

  @BeforeEach
  void setUp() {
    time = TestTimes.clock();
    time.set(NOW);
    market = new RecordingMarketData(time);
    market.withAsset("AAPL").quote("AAPL", NOW, "227.40", 100, "227.42", 200);
    players = mock(PlayerQueries.class);
    when(players.status(anyLong())).thenReturn(PlayerStatus.ACTIVE);
    hub = hub(StreamProperties.defaults());
  }

  StreamHub hub(StreamProperties properties) {
    MarketCalendar calendar = mock(MarketCalendar.class);
    when(calendar.stateAt(org.mockito.ArgumentMatchers.any())).thenReturn(new SessionState(SessionType.REGULAR,
        TestTimes.TRADE_DATE, NOW.minusSeconds(1800), NOW.plusSeconds(21600), SessionType.POST,
        NOW.plusSeconds(21600)));
    MarketSessionTopic sessions = new MarketSessionTopic(calendar, time, null, null);
    return new StreamHub(properties, time, JSON, market, new QuoteMessages(time, calendar),
        provider(AccountQueries.class, null), provider(LeaderboardService.class, null),
        provider(MarketSessionTopic.class, sessions), players, jdbc);
  }

  @AfterEach
  void tearDown() {
    for (StreamConnection c : opened) {
      c.close(com.stocks.contract.realtime.dto.StreamByeDto.Reason.SHUTDOWN);
      c.await(2_000);
    }
    if (db != null) {
      db.close();
    }
  }

  static PlayerPrincipal key(long playerId, Set<String> scopes, long keyId) {
    return TestPrincipals.apiKey(playerId, "ply_" + playerId, "p" + playerId, PlayerKind.AI, scopes, keyId);
  }

  static PlayerPrincipal key(long playerId) {
    return key(playerId, Scopes.ALL, playerId * 10);
  }

  Sink open(StreamHub h, PlayerPrincipal p, Set<StreamTopic> topics, List<String> symbols, String lastId) {
    Sink sink = new Sink();
    opened.add(h.open(p, topics, symbols, lastId, sink));
    return sink;
  }

  Sink open(long playerId, Set<StreamTopic> topics, String lastId) {
    return open(hub, key(playerId), topics, List.of(), lastId);
  }

  static Map<String, Object> order(String id) {
    return Map.of("event", "FILL", "order", Map.of("orderId", id), "execution", Map.of());
  }

  @Test
  void aStreamStartsWithRetryAndHelloThenCarriesLiveEventsWithIds() throws Exception {
    Sink a = open(1, EnumSet.of(StreamTopic.ORDERS, StreamTopic.FEED, StreamTopic.SESSION), null);
    Frame retry = a.next();
    assertThat(retry.event()).isEqualTo("retry");
    assertThat(retry.raw()).isEqualTo("retry: 3000\n\n");
    Frame hello = a.next();
    assertThat(hello.event()).isEqualTo("hello");
    assertThat(hello.id()).isNull();
    assertThat(hello.json().get("connectionId").asString()).startsWith("sse_" + hub.epoch());
    assertThat(hello.json().get("topics").toString()).isEqualTo("[\"SESSION\",\"ORDERS\",\"FEED\"]");
    assertThat(hello.json().get("resumed").asBoolean()).isFalse();
    assertThat(hello.json().get("heartbeatSeconds").asInt()).isEqualTo(15);
    assertThat(hello.json().get("maxAgeSeconds").asInt()).isEqualTo(1800);
    Frame session = a.next();
    assertThat(session.event()).isEqualTo("session");
    assertThat(session.json().get("session").asString()).isEqualTo("REGULAR");

    hub.publishPrivate(1, StompDestinations.ORDERS_QUEUE, order("ord_1"));
    hub.publishPublic(StompDestinations.FEED_TOPIC, "{\"id\":\"fde_1\"}");
    hub.publishPublic(StompDestinations.LEADERBOARD_TOPIC, "{\"asOf\":\"x\"}");   // not requested
    Frame o = a.next();
    assertThat(o.event()).isEqualTo("order");
    assertThat(o.id()).isEqualTo(hub.epoch() + "-1");
    assertThat(o.json().get("order").get("orderId").asString()).isEqualTo("ord_1");
    Frame f = a.next();
    assertThat(f.event()).isEqualTo("feed");
    assertThat(f.id()).isEqualTo(hub.epoch() + "-2");
    assertThat(f.data()).isEqualTo("{\"id\":\"fde_1\"}");
    assertThat(a.quietFor(300)).isTrue();
  }

  @Test
  void onePlayersEventsNeverReachAnotherPlayersStream() throws Exception {
    Sink a = open(1, EnumSet.of(StreamTopic.ORDERS, StreamTopic.ACCOUNT), null);
    Sink b = open(2, EnumSet.of(StreamTopic.ORDERS, StreamTopic.ACCOUNT), null);
    a.until("hello");
    b.until("hello");
    hub.publishPrivate(1, StompDestinations.ORDERS_QUEUE, order("ord_secret"));
    hub.publishPrivate(1, StompDestinations.NOTIFICATIONS_QUEUE, Map.of("id", "ntf_secret"));
    hub.publishPrivate(1, StompDestinations.ACCOUNT_QUEUE, Map.of("accountId", "acc_secret"));
    hub.publishPrivate(1, StompDestinations.ADMIN_QUEUE, Map.of("title", "admin only"));
    assertThat(a.next().event()).isEqualTo("order");
    Frame n = a.next();
    assertThat(n.event()).isEqualTo("notification");
    assertThat(n.id()).isNotNull();
    Frame acc = a.next();
    assertThat(acc.event()).isEqualTo("account");
    assertThat(acc.id()).as("account snapshots are not resumable").isNull();
    assertThat(a.quietFor(200)).as("the admin queue is not streamed").isTrue();
    assertThat(b.quietFor(300)).isTrue();

    // replays apply the same audience check: B resuming from before A's events gets none of them
    hub.publishPublic(StompDestinations.FEED_TOPIC, "{\"id\":\"fde_2\"}");
    Sink b2 = open(2, EnumSet.of(StreamTopic.ORDERS, StreamTopic.ACCOUNT, StreamTopic.FEED), hub.epoch() + "-0");
    List<Frame> frames = b2.until("feed");
    assertThat(frames).extracting(Frame::event).containsExactly("retry", "hello", "feed");
    assertThat(frames.get(1).json().get("replayed").asInt()).isEqualTo(1);
    assertThat(String.join("", frames.stream().map(Frame::raw).toList())).doesNotContain("secret");
  }

  @Test
  void aReconnectWithLastEventIdReplaysWhatWasMissedInOrder() throws Exception {
    Sink a = open(1, EnumSet.of(StreamTopic.ORDERS, StreamTopic.ACCOUNT, StreamTopic.FEED), null);
    a.until("hello");
    hub.publishPrivate(1, StompDestinations.ORDERS_QUEUE, order("ord_1"));
    String lastId = a.next().id();
    opened.get(0).close(com.stocks.contract.realtime.dto.StreamByeDto.Reason.MAX_AGE);
    assertThat(a.until("bye").getLast().json().get("reason").asString()).isEqualTo("MAX_AGE");
    awaitClosed(1);

    // offline (inside the reconnect window): private events are still recorded
    assertThat(hub.wantsPrivate(1)).isTrue();
    hub.publishPrivate(1, StompDestinations.ORDERS_QUEUE, order("ord_2"));
    hub.publishPrivate(1, StompDestinations.NOTIFICATIONS_QUEUE, Map.of("id", "ntf_1"));
    hub.publishPublic(StompDestinations.FEED_TOPIC, "{\"id\":\"fde_1\"}");
    hub.publishPrivate(1, StompDestinations.ACCOUNT_QUEUE, Map.of("accountId", "acc_1"));    // not resumable

    Sink again = open(1, EnumSet.of(StreamTopic.ORDERS, StreamTopic.FEED), lastId);
    List<Frame> frames = again.until("feed");
    assertThat(frames).extracting(Frame::event).containsExactly("retry", "hello", "order", "feed");
    assertThat(frames.get(1).json().get("resumed").asBoolean()).isTrue();
    assertThat(frames.get(1).json().get("replayed").asInt()).isEqualTo(2);     // not the notification: not asked
    assertThat(frames.get(2).json().get("order").get("orderId").asString()).isEqualTo("ord_2");
    assertThat(frames.get(2).id()).isEqualTo(hub.epoch() + "-2");
    assertThat(frames.get(3).id()).isEqualTo(hub.epoch() + "-4");
  }

  @Test
  void idsThatCannotBeHonouredStartWithAReset() throws Exception {
    hub.publishPublic(StompDestinations.FEED_TOPIC, "{\"id\":\"fde_1\"}");
    assertThat(reset(hub, "zzzzzzzz-1")).isEqualTo("RESTARTED");
    assertThat(reset(hub, "not an id")).isEqualTo("UNKNOWN");
    assertThat(reset(hub, hub.epoch() + "-99")).isEqualTo("UNKNOWN");      // from the future

    StreamHub small = hub(new StreamProperties(true, 3, 200, Duration.ofSeconds(15), Duration.ofMinutes(30),
        Duration.ofSeconds(30), 2, Duration.ofMinutes(5), Duration.ofSeconds(1), 1000, 3000, 50));
    for (int i = 1; i <= 4; i++) {
      small.publishPublic(StompDestinations.FEED_TOPIC, "{\"id\":\"fde_" + i + "\"}");
    }
    assertThat(reset(small, small.epoch() + "-1")).isEqualTo("EXPIRED");        // 2 was evicted
    Sink ok = open(small, key(5), EnumSet.of(StreamTopic.FEED), List.of(), small.epoch() + "-2");
    assertThat(ok.until("feed").get(1).json().get("replayed").asInt()).isEqualTo(2);

    // too old: older than buffer-max-age
    time.set(NOW.plus(Duration.ofMinutes(6)));
    small.publishPublic(StompDestinations.FEED_TOPIC, "{\"id\":\"fde_5\"}");
    assertThat(reset(small, small.epoch() + "-3")).isEqualTo("EXPIRED");      // 4 aged out
  }

  String reset(StreamHub h, String lastId) throws Exception {
    Sink s = open(h, key(9), EnumSet.of(StreamTopic.FEED), List.of(), lastId);
    List<Frame> frames = s.until("reset");
    assertThat(frames.get(1).json().get("resumed").asBoolean()).isFalse();
    assertThat(frames.get(1).json().get("replayed").asInt()).isZero();
    opened.getLast().close(com.stocks.contract.realtime.dto.StreamByeDto.Reason.SHUTDOWN);
    s.until("bye");
    return frames.getLast().json().get("reason").asString();
  }

  @Test
  void afterTheReconnectWindowThePlayersOldIdsExpire() throws Exception {
    Sink a = open(1, EnumSet.of(StreamTopic.ORDERS), null);
    a.until("hello");
    hub.publishPrivate(1, StompDestinations.ORDERS_QUEUE, order("ord_1"));
    String lastId = a.next().id();
    opened.get(0).close(com.stocks.contract.realtime.dto.StreamByeDto.Reason.MAX_AGE);
    a.until("bye");
    awaitClosed(1);
    time.set(NOW.plus(Duration.ofMinutes(6)));
    assertThat(hub.wantsPrivate(1)).isFalse();                            // the order is not recorded
    hub.publishPrivate(1, StompDestinations.ORDERS_QUEUE, order("ord_lost"));
    Sink again = open(1, EnumSet.of(StreamTopic.ORDERS), lastId);
    assertThat(again.until("reset").getLast().json().get("reason").asString()).isEqualTo("EXPIRED");
    assertThat(hub.wantsPrivate(2)).isFalse();                            // never had a stream
  }

  @Test
  void capsPerPlayerAndInTotal() {
    for (int i = 0; i < 3; i++) {
      open(1, EnumSet.of(StreamTopic.FEED), null);
    }
    assertThatThrownBy(() -> open(1, EnumSet.of(StreamTopic.FEED), null)).isInstanceOfSatisfying(ApiException.class,
        e -> {
          assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
          assertThat(e.retryAfterS()).isEqualTo(30);
        });
    open(2, EnumSet.of(StreamTopic.FEED), null);                          // another player is fine
    StreamHub tiny = hub(new StreamProperties(true, 3, 1, Duration.ofSeconds(15), Duration.ofMinutes(30),
        Duration.ofSeconds(30), 10, Duration.ofMinutes(5), Duration.ofSeconds(1), 1000, 3000, 50));
    open(tiny, key(1), EnumSet.of(StreamTopic.FEED), List.of(), null);
    assertThatThrownBy(() -> open(tiny, key(2), EnumSet.of(StreamTopic.FEED), List.of(), null))
        .isInstanceOf(ApiException.class);
    assertThat(hub.openStreams()).isEqualTo(4);
    assertThat(hub.streamsOf(1)).hasSize(3);
  }

  @Test
  void quotesAreSentAtConnectThenCoalescedPerSymbolAndLeased() throws Exception {
    StreamHub fast = hub(new StreamProperties(true, 3, 200, Duration.ofSeconds(15), Duration.ofMinutes(30),
        Duration.ofSeconds(30), 10, Duration.ofMinutes(5), Duration.ofMillis(400), 1000, 3000, 50));
    Sink s = open(fast, key(1, Set.of(Scopes.MARKET_READ), 10), EnumSet.of(StreamTopic.QUOTES), List.of("AAPL"),
        null);
    String id = opened.getLast().id();
    Frame q = s.until("quote").getLast();
    assertThat(q.json().get("symbol").asString()).isEqualTo("AAPL");
    assertThat(q.json().get("bid").asString()).isEqualTo("227.40");          // §6.3 decimal strings
    assertThat(market.lease("AAPL", StreamHub.ownerKey(id))).isPresent();
    assertThat(market.lease("AAPL", StreamHub.ownerKey(id)).get().priority().name()).isEqualTo("LIST");

    for (int i = 0; i < 5; i++) {
      fast.quoteChanged("AAPL");
      fast.quoteChanged("MSFT");                                           // not watched
    }
    assertThat(s.next().event()).isEqualTo("quote");                       // one, after the interval
    assertThat(s.quietFor(600)).isTrue();
    assertThat(fast.watches("AAPL")).isTrue();
    assertThat(fast.watches("MSFT")).isFalse();

    opened.getLast().close(com.stocks.contract.realtime.dto.StreamByeDto.Reason.SHUTDOWN);
    s.until("bye");
    awaitClosed(fast, 1);
    assertThat(market.lease("AAPL", StreamHub.ownerKey(id))).isEmpty();
    assertThat(fast.watches("AAPL")).isFalse();
  }

  @Test
  void heartbeatsAndMaxAge() throws Exception {
    StreamHub quick = hub(new StreamProperties(true, 3, 200, Duration.ofMillis(200), Duration.ofMillis(900),
        Duration.ofSeconds(30), 10, Duration.ofMinutes(5), Duration.ofSeconds(1), 1000, 3000, 50));
    Sink s = open(quick, key(1), EnumSet.of(StreamTopic.FEED), List.of(), null);
    boolean heartbeat = false;
    Frame bye = null;
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < end && bye == null) {
      String f = s.frames.poll(100, TimeUnit.MILLISECONDS);
      if (f == null) {
        continue;
      }
      if (f.startsWith(": hb ")) {
        heartbeat = true;
      } else if (f.contains("event: bye")) {
        bye = Frame.parse(f);
      }
    }
    assertThat(heartbeat).isTrue();
    assertThat(bye).isNotNull();
    assertThat(bye.json().get("reason").asString()).isEqualTo("MAX_AGE");
    awaitClosed(quick, 1);
    assertThat(s.closed).isTrue();
  }

  @Test
  void revokedKeysAndFrozenPlayersAreClosed() throws Exception {
    db = TestDb.fresh();
    jdbc = db.jdbc();
    Fixtures fx = new Fixtures(jdbc, time);
    Fixtures.PlayerRef owner = fx.human("owner");
    Fixtures.PlayerRef bot = fx.ai("claude", owner);
    String token = fx.apiKey(bot, "pepper");
    long keyId = jdbc.sql("SELECT id FROM api_key WHERE player_id = ?").param(bot.id()).query(Long.class).single();
    StreamHub withDb = hub(StreamProperties.defaults());
    Sink s = open(withDb, key(bot.id(), Scopes.ALL, keyId), EnumSet.of(StreamTopic.FEED), List.of(), null);
    Sink other = open(withDb, key(owner.id()), EnumSet.of(StreamTopic.FEED), List.of(), null);
    s.until("hello");
    other.until("hello");
    when(players.status(owner.id())).thenReturn(PlayerStatus.ACTIVE);
    assertThat(withDb.revalidate()).isEqualTo(1);                          // the owner's key id is not a row
    assertThat(other.until("bye").getLast().json().get("reason").asString()).isEqualTo("REVOKED");
    assertThat(s.quietFor(200)).isTrue();
    fx.revokeApiKey(token);
    assertThat(withDb.revalidate()).isEqualTo(1);
    assertThat(s.until("bye").getLast().json().get("reason").asString()).isEqualTo("REVOKED");

    Sink third = open(withDb, key(7), EnumSet.of(StreamTopic.FEED), List.of(), null);
    third.until("hello");
    withDb.on(new PlayerStatusChanged(7, PlayerStatus.FROZEN, NOW));
    assertThat(third.until("bye").getLast().json().get("reason").asString()).isEqualTo("REVOKED");
  }

  @Test
  void aClientThatFallsTooFarBehindIsClosed() throws Exception {
    StreamHub small = hub(new StreamProperties(true, 3, 200, Duration.ofSeconds(15), Duration.ofMinutes(30),
        Duration.ofSeconds(30), 100, Duration.ofMinutes(5), Duration.ofSeconds(1), 3, 3000, 50));
    Sink s = new Sink();
    s.gate = new CountDownLatch(1);
    opened.add(small.open(key(1), EnumSet.of(StreamTopic.FEED), List.of(), null, s));
    Thread.sleep(200);                                                     // the sender is stuck on "retry"
    for (int i = 0; i < 10; i++) {
      small.publishPublic(StompDestinations.FEED_TOPIC, "{\"id\":\"fde_" + i + "\"}");
    }
    s.gate.countDown();
    s.gate = null;
    List<Frame> frames = s.until("bye");
    assertThat(frames.getLast().json().get("reason").asString()).isEqualTo("SLOW_CONSUMER");
    assertThat(frames.stream().filter(f -> "feed".equals(f.event())).count()).isLessThan(10);
  }

  @Test
  void theShutdownSaysByeToEveryStream() throws Exception {
    Sink a = open(1, EnumSet.of(StreamTopic.FEED), null);
    Sink b = open(2, EnumSet.of(StreamTopic.FEED), null);
    hub.start();
    hub.stop();
    assertThat(a.until("bye").getLast().json().get("reason").asString()).isEqualTo("SHUTDOWN");
    assertThat(b.until("bye").getLast().json().get("reason").asString()).isEqualTo("SHUTDOWN");
    assertThat(hub.isRunning()).isFalse();
  }

  @Test
  void framesSplitMultilineDataAndOmitIdsOfSnapshots() {
    assertThat(StreamHub.frame(null, "x", "{\"a\":1}")).isEqualTo("event: x\ndata: {\"a\":1}\n\n");
    assertThat(StreamHub.frame("e-1", "x", "a\nb")).isEqualTo("id: e-1\nevent: x\ndata: a\ndata: b\n\n");
    assertThat(StreamHub.privateRoute(StompDestinations.ADMIN_QUEUE)).isNull();
    assertThat(StreamHub.publicRoute("/topic/quote.AAPL")).isNull();
  }

  void awaitClosed(long playerId) throws InterruptedException {
    awaitClosed(hub, playerId);
  }

  static void awaitClosed(StreamHub h, long playerId) throws InterruptedException {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!h.streamsOf(playerId).isEmpty() && System.nanoTime() < end) {
      Thread.sleep(20);
    }
    assertThat(h.streamsOf(playerId)).isEmpty();
  }
}
