package com.stocks.realtime.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.apilog.ApiAccessLog;
import com.stocks.common.domain.NotificationKind;
import com.stocks.config.StocksProperties;
import com.stocks.realtime.NotificationService;
import com.stocks.realtime.StompDestinations;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestClockConfig;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.test.context.ActiveProfiles;
import com.stocks.common.time.MutableTimeSource;

/**
 * §19.3 over real HTTP (Tomcat on a random port, the real chains and STOMP broker): the proxy-safe headers, hello,
 * live private events reaching only their player, broadcast topics teed from the broker channel, a disconnect and a
 * resume with {@code Last-Event-ID} that replays what was missed, and the access log entry of the stream request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=-1", "stocks.stream.heartbeat=300ms"})
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class StreamIT {

  static final AtomicInteger SEQ = new AtomicInteger();

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;
  @Autowired NotificationService notifications;
  @Autowired SimpMessageSendingOperations messaging;
  @Autowired StreamHub hub;
  @Autowired ApiAccessLog accessLog;

  final HttpClient http = HttpClient.newHttpClient();
  final List<Stream> streams = new ArrayList<>();
  Fixtures fx;
  PlayerRef alice;
  PlayerRef bob;
  String aliceKey;
  String bobKey;

  /** One open stream: frames read on a virtual thread until the body ends or the test closes it. */
  static final class Stream implements AutoCloseable {
    final HttpResponse<InputStream> response;
    final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    final Thread reader;

    Stream(HttpResponse<InputStream> response) {
      this.response = response;
      this.reader = Thread.ofVirtual().start(() -> {
        StringBuilder frame = new StringBuilder();
        try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),
            StandardCharsets.UTF_8))) {
          String line;
          while ((line = in.readLine()) != null) {
            if (line.isEmpty()) {
              frames.add(frame.toString());
              frame.setLength(0);
            } else {
              frame.append(line).append('\n');
            }
          }
        } catch (Exception e) {
          // closed by the test
        }
      });
    }

    /** The next frame that is not a heartbeat, or null after 5 s. */
    String next() throws InterruptedException {
      while (true) {
        String f = frames.poll(5, TimeUnit.SECONDS);
        if (f == null || !f.startsWith(":")) {
          return f;
        }
      }
    }

    String until(String event) throws InterruptedException {
      String f;
      while ((f = next()) != null) {
        if (f.contains("event: " + event + "\n")) {
          return f;
        }
      }
      throw new AssertionError("no " + event);
    }

    boolean heartbeat() throws InterruptedException {
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < end) {
        String f = frames.poll(100, TimeUnit.MILLISECONDS);
        if (f != null && f.startsWith(": hb ")) {
          return true;
        }
      }
      return false;
    }

    @Override
    public void close() throws Exception {
      response.body().close();
      reader.interrupt();
    }
  }

  @BeforeEach
  void setUp() {
    fx = new Fixtures(jdbc, time);
    int n = SEQ.incrementAndGet();
    PlayerRef owner = fx.human("sseowner" + n);
    alice = fx.ai("ssealice" + n, owner);
    bob = fx.ai("ssebob" + n, owner);
    aliceKey = fx.apiKey(alice, properties.security().apiKeyPepper());
    bobKey = fx.apiKey(bob, properties.security().apiKeyPepper());
  }

  @AfterEach
  void tearDown() throws Exception {
    for (Stream s : streams) {
      s.close();
    }
  }

  Stream open(String key, String query, String lastEventId) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/stream" + query))
        .header("Authorization", "Bearer " + key).header("Accept", "text/event-stream").GET();
    if (lastEventId != null) {
      b.header("Last-Event-ID", lastEventId);
    }
    Stream s = new Stream(http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream()));
    streams.add(s);
    return s;
  }

  static String id(String frame) {
    for (String line : frame.split("\n")) {
      if (line.startsWith("id: ")) {
        return line.substring(4);
      }
    }
    return null;
  }

  void notify(PlayerRef p, String ref) {
    notifications.notify(p.id(), NotificationKind.FILL, "Bought 1 NVDA " + ref, "body " + ref,
        Map.of("orderId", "ord_" + ref), "TEST:" + ref);
  }

  @Test
  void privateEventsReachOnlyTheirPlayerAndResumeAfterADisconnect() throws Exception {
    Stream a = open(aliceKey, "?topics=account,orders,feed", null);
    assertThat(a.response.statusCode()).isEqualTo(200);
    assertThat(a.response.headers().firstValue("Content-Type")).hasValue("text/event-stream;charset=UTF-8");
    assertThat(a.response.headers().firstValue("Cache-Control")).hasValue("no-store, no-transform");
    assertThat(a.response.headers().firstValue("X-Accel-Buffering")).hasValue("no");
    assertThat(a.response.headers().firstValue("Content-Encoding")).isEmpty();
    assertThat(a.next()).isEqualTo("retry: 3000\n");
    assertThat(a.until("hello")).contains("\"resumed\":false").contains("\"ACCOUNT\"");
    Stream b = open(bobKey, "?topics=account,orders", null);
    b.until("hello");

    notify(alice, "a1-" + SEQ.get());
    String first = a.until("notification");
    assertThat(first).contains("Bought 1 NVDA a1-");
    String firstId = id(first);
    assertThat(firstId).startsWith(hub.epoch() + "-");

    // a broadcast topic, published on the STOMP broker channel like WS4's FeedService does
    messaging.convertAndSend(StompDestinations.FEED_TOPIC, (Object) Map.of("id", "fde_it", "kind", "JOURNAL"));
    assertThat(a.until("feed")).contains("\"fde_it\"");
    assertThat(b.heartbeat()).isTrue();
    assertThat(b.frames.stream().filter(f -> !f.startsWith(":")).toList()).isEmpty();   // nothing of Alice's

    // Alice disconnects; the next heartbeat write fails and the server closes her stream
    a.close();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!hub.streamsOf(alice.id()).isEmpty() && System.nanoTime() < end) {
      Thread.sleep(50);
    }
    assertThat(hub.streamsOf(alice.id())).isEmpty();
    notify(alice, "a2-" + SEQ.get());                                   // missed while offline

    Stream again = open(aliceKey, "?topics=account,orders,feed", firstId);
    String hello = again.until("hello");
    assertThat(hello).contains("\"resumed\":true").contains("\"replayed\":2");
    assertThat(again.next()).contains("event: feed");
    assertThat(again.next()).contains("event: notification").contains("Bought 1 NVDA a2-");

    // the stream request itself is in the API access log
    accessLog.flush();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM api_access_log WHERE player_id = ? AND endpoint = "
        + "'GET /api/v1/stream' AND outcome = '200'").param(alice.id()).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void quotesAndSessionSnapshotsArriveAtConnect() throws Exception {
    Stream s = open(bobKey, "?topics=quotes,session&symbols=aapl", null);
    String hello = s.until("hello");
    assertThat(hello).contains("\"symbols\":[\"AAPL\"]");
    List<String> next = new ArrayList<>();
    next.add(s.next());
    next.add(s.next());
    assertThat(String.join("", next)).contains("event: session").contains("event: quote")
        .contains("\"symbol\":\"AAPL\"");
  }

  @Test
  void beyondThreeStreamsAPlayerGets429() throws Exception {
    for (int i = 0; i < 3; i++) {
      open(bobKey, "?topics=feed", null).until("hello");
    }
    HttpResponse<String> fourth = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
        + "/api/v1/stream?topics=feed")).header("Authorization", "Bearer " + bobKey).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(fourth.statusCode()).isEqualTo(429);
    assertThat(fourth.headers().firstValue("Retry-After")).hasValue("30");
    assertThat(fourth.body()).contains("RATE_LIMITED");
  }
}
