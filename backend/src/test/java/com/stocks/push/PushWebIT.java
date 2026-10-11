package com.stocks.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.stocks.alerts.AlertMonitor;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.security.Scopes;
import com.stocks.portfolio.ScriptedMarketConfig;
import com.stocks.push.FakePushService.Browser;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.ScriptedMarketData;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.WebSession;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * §18.1 {@code /me/push/**} through the real chains and Spring Session, with a local push service: session only (an
 * API key gets 403), CSRF, registration bound to the session (logout removes it, a password change removes all),
 * sync after signing in again, the test push, and the whole path a price alert takes: REST create → live price →
 * {@code AlertMonitor} fires → {@code PRICE_ALERT} notification → encrypted Web Push the browser decrypts.
 */
@SpringBootTest(properties = "stocks.push.allow-insecure-endpoints=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, ScriptedMarketConfig.class})
class PushWebIT {

  static final AtomicInteger SEQ = new AtomicInteger();
  static FakePushService service;

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;
  @Autowired AlertMonitor monitor;
  @Autowired MarketDataService marketData;

  PlayerRef alice;
  WebSession session;

  @BeforeAll
  static void startService() throws Exception {
    service = new FakePushService();
  }

  @AfterAll
  static void stopService() {
    service.close();
  }

  @BeforeEach
  void setUp() {
    alice = new Fixtures(jdbc, time).human("pw_alice" + SEQ.incrementAndGet());
    session = WebSession.login(mvc, alice.handle(), Fixtures.PASSWORD);
    service.received.clear();
  }

  static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, String body) {
    return r.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  static <T> T read(MvcResult r, String path) {
    return JsonPath.read(WebSession.contentOf(r), path);
  }

  static String subscription(Browser b) {
    return "{\"endpoint\":\"" + b.endpoint() + "\",\"p256dh\":\"" + b.p256dh() + "\",\"auth\":\"" + b.authB64()
        + "\"}";
  }

  MvcResult subscribe(WebSession s, Browser b) {
    return s.perform(json(post("/api/v1/me/push/subscriptions"), subscription(b))
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/129.0 Mobile Safari/537.36"));
  }

  @Test
  void settingsAreSessionOnlyAndNeverRevealTheEndpoint() {
    MvcResult settings = session.perform(get("/api/v1/me/push"));
    assertThat(settings.getResponse().getStatus()).isEqualTo(200);
    assertThat((Boolean) read(settings, "$.available")).isTrue();
    assertThat((String) read(settings, "$.vapidPublicKey")).hasSize(87);
    assertThat((Boolean) read(settings, "$.preferences.alerts")).isTrue();

    String key = new Fixtures(jdbc, time).apiKey(alice, Scopes.ALL, properties.security().apiKeyPepper());
    MvcResult byKey;
    try {
      byKey = mvc.perform(get("/api/v1/me/push").header("Authorization", "Bearer " + key)).andReturn();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    assertThat(byKey.getResponse().getStatus()).isEqualTo(403);
    assertThat((String) read(byKey, "$.code")).isEqualTo("FORBIDDEN");

    Browser b = service.subscription();
    MvcResult noCsrf = session.performWithoutCsrf(json(post("/api/v1/me/push/subscriptions"), subscription(b)));
    assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);

    MvcResult created = subscribe(session, b);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertThat((String) read(created, "$.label")).isEqualTo("Chrome on Android");
    assertThat((Boolean) read(created, "$.current")).isTrue();
    MvcResult listed = session.perform(get("/api/v1/me/push"));
    assertThat((List<String>) read(listed, "$.devices[*].id")).containsExactly((String) read(created, "$.id"));
    assertThat(WebSession.contentOf(listed)).doesNotContain(b.endpoint()).doesNotContain(b.p256dh())
        .doesNotContain(b.authB64());

    MvcResult rejected = session.perform(json(post("/api/v1/me/push/subscriptions"),
        "{\"endpoint\":\"ftp://127.0.0.1/x\",\"p256dh\":\"" + b.p256dh() + "\",\"auth\":\"" + b.authB64() + "\"}"));
    assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
    assertThat((String) read(rejected, "$.code")).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void loggingOutRemovesThisDeviceAndSyncTellsTheBrowserAfterwards() {
    Browser b = service.subscription();
    subscribe(session, b);
    assertThat(session.perform(post("/api/v1/auth/logout")).getResponse().getStatus()).isEqualTo(204);
    WebSession again = WebSession.login(mvc, alice.handle(), Fixtures.PASSWORD);
    assertThat((List<?>) read(again.perform(get("/api/v1/me/push")), "$.devices")).isEmpty();
    MvcResult sync = again.perform(json(post("/api/v1/me/push/sync"), "{\"endpoint\":\"" + b.endpoint() + "\"}"));
    assertThat((Boolean) read(sync, "$.registered")).isFalse();
    // registering again from the new session, then a sync confirms it
    subscribe(again, b);
    MvcResult ok = again.perform(json(post("/api/v1/me/push/sync"), "{\"endpoint\":\"" + b.endpoint() + "\"}"));
    assertThat((Boolean) read(ok, "$.registered")).isTrue();
    assertThat((Boolean) read(ok, "$.device.current")).isTrue();
    // turning it off on this device
    assertThat(again.perform(json(post("/api/v1/me/push/unsubscribe"), "{\"endpoint\":\"" + b.endpoint() + "\"}"))
        .getResponse().getStatus()).isEqualTo(204);
    assertThat((List<?>) read(again.perform(get("/api/v1/me/push")), "$.devices")).isEmpty();
  }

  @Test
  void aPasswordChangeRemovesEveryDevice() {
    subscribe(session, service.subscription());
    WebSession laptop = WebSession.login(mvc, alice.handle(), Fixtures.PASSWORD);
    subscribe(laptop, service.subscription());
    assertThat((List<?>) read(session.perform(get("/api/v1/me/push")), "$.devices")).hasSize(2);
    MvcResult changed = session.perform(json(post("/api/v1/me/password"),
        "{\"currentPassword\":\"" + Fixtures.PASSWORD + "\",\"newPassword\":\"another-password-123\"}"));
    assertThat(changed.getResponse().getStatus()).isEqualTo(204);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM push_subscription WHERE player_id = ?").param(alice.id())
        .query(Integer.class).single()).isZero();
  }

  @Test
  void devicesCanBeRemovedAndTheTestPushReachesThem() throws Exception {
    Browser b = service.subscription();
    String id = read(subscribe(session, b), "$.id");
    MvcResult test = session.perform(post("/api/v1/me/push/test"));
    assertThat(test.getResponse().getStatus()).isEqualTo(200);
    assertThat((Integer) read(test, "$.devices")).isEqualTo(1);
    assertThat((Integer) read(test, "$.delivered")).isEqualTo(1);
    assertThat(b.decrypt(service.received.get(0).body())).contains("Test notification");
    MvcResult tooSoon = session.perform(post("/api/v1/me/push/test"));
    assertThat(tooSoon.getResponse().getStatus()).isEqualTo(429);
    assertThat(tooSoon.getResponse().getHeader("Retry-After")).isNotNull();
    assertThat(session.perform(delete("/api/v1/me/push/subscriptions/" + id)).getResponse().getStatus())
        .isEqualTo(204);
    assertThat(session.perform(delete("/api/v1/me/push/subscriptions/" + id)).getResponse().getStatus())
        .isEqualTo(404);
  }

  @Test
  void aFiredPriceAlertArrivesAsAnEncryptedPush() throws Exception {
    Browser b = service.subscription();
    subscribe(session, b);
    MvcResult pref = session.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .patch("/api/v1/me/push/preferences"), "{\"fills\":false}"));
    assertThat((Boolean) read(pref, "$.fills")).isFalse();
    assertThat((Boolean) read(pref, "$.alerts")).isTrue();
    MvcResult alert = session.perform(json(post("/api/v1/alerts"),
        "{\"symbol\":\"SIMB\",\"condition\":\"PRICE_GTE\",\"threshold\":\"50.00\",\"note\":\"breakout\"}"));
    assertThat(alert.getResponse().getStatus()).isEqualTo(201);
    ScriptedMarketData market = (ScriptedMarketData) marketData;
    time.advance(java.time.Duration.ofSeconds(1));
    market.prevClose("SIMB", "48.00").print("SIMB", time.now(), "50.25", 100);
    monitor.changed("SIMB");                                    // as the QuoteBook callback would mark it
    monitor.evaluate();
    service.await(1);
    assertThat(service.received).hasSize(1);
    FakePushService.Received r = service.received.get(0);
    assertThat(r.header("Urgency")).isEqualTo("high");
    String payload = b.decrypt(r.body());
    assertThat(payload).contains("\"kind\":\"PRICE_ALERT\"").contains("\"title\":\"SIMB ≥ $50.00\"")
        .contains("\"url\":\"/stock/SIMB\"").contains("Note: breakout");
    assertThat(WebSession.contentOf(session.perform(get("/api/v1/me/notifications")))).contains("SIMB ≥ $50.00");
  }
}
