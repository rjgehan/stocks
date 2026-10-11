package com.stocks.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.push.FakePushService.Browser;
import com.stocks.push.FakePushService.Received;
import com.stocks.push.FakePushService.Reply;
import com.stocks.push.PushDispatcher.Delivery;
import com.stocks.push.PushSender.Result;
import com.stocks.push.PushSubscriptions.Subscription;
import com.stocks.testkit.Fixtures.PlayerRef;
import java.net.URI;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * §19.2 delivery against a local push service: the request is a valid RFC 8291 {@code aes128gcm} message the browser
 * can decrypt, signed with a VAPID JWT (RFC 8292) for the push service's origin; 404/410 deletes the subscription,
 * 429/5xx/I-O errors are retried, other failures counted, a moved subscription is never sent another player's message.
 */
class PushDeliveryTest {

  PushKit k;
  PlayerRef alice;
  PlayerRef bob;

  @BeforeEach
  void setUp() throws Exception {
    k = new PushKit();
    alice = k.fx.human("alice");
    bob = k.fx.human("bob");
  }

  @AfterEach
  void tearDown() {
    k.close();
  }

  Subscription register(PlayerRef player, Browser b) {
    return k.subscriptions.upsert("psh_" + player.handle() + b.endpoint().hashCode(), player.id(), b.endpoint(),
        b.p256dh(), b.authB64(), null, "Test", k.time.now());
  }

  @Test
  void theRequestIsAnEncryptedVapidSignedWebPush() throws Exception {
    Browser b = k.service.subscription();
    Subscription s = register(alice, b);
    PushMessage msg = PushMessage.test();
    assertThat(k.dispatcher.deliver(s, msg).get(10, TimeUnit.SECONDS)).isEqualTo(Delivery.DELIVERED);

    Received r = k.service.received.get(0);
    assertThat(r.path()).isEqualTo(URI.create(b.endpoint()).getPath());
    assertThat(r.header("Content-Encoding")).isEqualTo("aes128gcm");
    assertThat(r.header("Content-Type")).isEqualTo("application/octet-stream");
    assertThat(r.header("TTL")).isEqualTo("600");
    assertThat(r.header("Urgency")).isEqualTo("normal");
    assertThat(r.header("Topic")).isEqualTo("test");
    // the browser decrypts exactly the JSON the service worker reads
    JsonNode payload = com.stocks.config.JacksonConfig.newMapper().readTree(b.decrypt(r.body()));
    assertThat(payload.get("v").asInt()).isEqualTo(1);
    assertThat(payload.get("title").asString()).isEqualTo("Test notification");
    assertThat(payload.get("url").asString()).isEqualTo("/settings/notifications");
    // VAPID: "vapid t=<jwt>, k=<our public key>", the JWT signed by that key for this origin
    String auth = r.header("Authorization");
    assertThat(auth).startsWith("vapid t=");
    String jwt = auth.substring("vapid t=".length(), auth.indexOf(", k="));
    String key = auth.substring(auth.indexOf(", k=") + 4);
    VapidKeys.Pair pair = k.keys.current().orElseThrow();
    assertThat(key).isEqualTo(pair.publicKeyB64());
    assertThat(VapidJwt.verify(jwt, (ECPublicKey) pair.publicKey())).isTrue();
    String claims = new String(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),
        java.nio.charset.StandardCharsets.UTF_8);
    assertThat(claims).contains("\"aud\":\"" + k.service.origin() + "\"").contains("\"sub\":\"mailto:admin@localhost\"")
        .contains("\"exp\":" + k.time.now().plus(Duration.ofHours(12)).getEpochSecond());
    assertThat(k.subscriptions.byId(s.id()).orElseThrow().lastSuccessAt()).isEqualTo(k.time.now());
  }

  @Test
  void goneSubscriptionsAreDeleted() throws Exception {
    Subscription s404 = register(alice, k.service.subscription());
    Subscription s410 = register(alice, k.service.subscription());
    k.service.reply(Reply.of(404), Reply.of(410));
    assertThat(k.dispatcher.deliver(s404, PushMessage.test()).get(10, TimeUnit.SECONDS)).isEqualTo(Delivery.REMOVED);
    assertThat(k.dispatcher.deliver(s410, PushMessage.test()).get(10, TimeUnit.SECONDS)).isEqualTo(Delivery.REMOVED);
    assertThat(k.subscriptions.forPlayer(alice.id())).isEmpty();
  }

  @Test
  void throttledAndFailingServicesAreRetriedThenCounted() throws Exception {
    Subscription s = register(alice, k.service.subscription());
    // 429 with Retry-After: 0, then 503, then 201: three attempts, delivered
    k.service.reply(new Reply(429, Map.of("Retry-After", "0")), new Reply(503, Map.of("Retry-After", "0")));
    assertThat(k.dispatcher.deliver(s, PushMessage.test()).get(10, TimeUnit.SECONDS)).isEqualTo(Delivery.DELIVERED);
    assertThat(k.service.received).hasSize(3);
    // three 500s: failed after max-attempts, one failure counted
    k.service.reply(new Reply(500, Map.of("Retry-After", "0")), new Reply(500, Map.of("Retry-After", "0")),
        new Reply(500, Map.of("Retry-After", "0")));
    assertThat(k.dispatcher.deliver(s, PushMessage.test()).get(10, TimeUnit.SECONDS)).isEqualTo(Delivery.FAILED);
    assertThat(k.subscriptions.byId(s.id()).orElseThrow().failureCount()).isEqualTo(1);
    // a 400 is not retried; after 10 consecutive failures the device is dropped
    k.db.jdbc().sql("UPDATE push_subscription SET failure_count = 8 WHERE id = ?").param(s.id()).update();
    k.service.reply(Reply.of(400));
    assertThat(k.dispatcher.deliver(s, PushMessage.test()).get(10, TimeUnit.SECONDS)).isEqualTo(Delivery.FAILED);
    k.service.reply(Reply.of(403));
    assertThat(k.dispatcher.deliver(s, PushMessage.test()).get(10, TimeUnit.SECONDS)).isEqualTo(Delivery.REMOVED);
    assertThat(k.subscriptions.byId(s.id())).isEmpty();
  }

  @Test
  void redirectsAreNeverFollowed() {
    Subscription s = register(alice, k.service.subscription());
    k.service.reply(new Reply(307, Map.of("Location", "http://169.254.169.254/latest/meta-data")));
    PushSender.Outcome o = k.sender.send(s, PushMessage.test(), PushMessage.test().payload(
        com.stocks.config.JacksonConfig.newMapper()));
    assertThat(o.result()).isEqualTo(Result.FAILED);
    assertThat(o.status()).isEqualTo(307);
    assertThat(k.service.received).hasSize(1);
  }

  @Test
  void aSubscriptionThatChangedHandsNeverGetsTheOldOwnersMessage() throws Exception {
    Browser b = k.service.subscription();
    Subscription mine = register(alice, b);
    register(bob, b);                                            // the same browser, now bob's
    assertThat(k.dispatcher.deliver(mine, PushMessage.test()).get(10, TimeUnit.SECONDS)).isEqualTo(Delivery.SKIPPED);
    assertThat(k.service.received).isEmpty();
  }

  @Test
  void endpointsOutsideTheAllowlistAreDroppedNotCalled() throws Exception {
    try (PushKit strict = new PushKit(PushProperties.defaults())) {
      PlayerRef carol = strict.fx.human("carol");
      Browser b = strict.service.subscription();                 // http://127.0.0.1: not a push service
      Subscription s = strict.subscriptions.upsert("psh_x", carol.id(), b.endpoint(), b.p256dh(), b.authB64(), null,
          "Test", strict.time.now());
      assertThat(strict.dispatcher.deliver(s, PushMessage.test()).get(10, TimeUnit.SECONDS))
          .isEqualTo(Delivery.REMOVED);
      assertThat(strict.service.received).isEmpty();
    }
  }

  @Test
  void dryRunEncryptsAndSignsButSendsNothing() throws Exception {
    PushProperties p = PushProperties.defaults().insecureForTests();
    PushProperties dry = new PushProperties(p.enabled(), p.vapid(), p.allowedEndpointHosts(), true, true, 2, 10,
        p.connectTimeout(), p.requestTimeout(), 3, 10);
    try (PushKit d = new PushKit(dry)) {
      PlayerRef carol = d.fx.human("carol");
      Browser b = d.service.subscription();
      Subscription s = d.subscriptions.upsert("psh_y", carol.id(), b.endpoint(), b.p256dh(), b.authB64(), null,
          "Test", d.time.now());
      assertThat(d.dispatcher.deliver(s, PushMessage.test()).get(10, TimeUnit.SECONDS)).isEqualTo(Delivery.DELIVERED);
      assertThat(d.service.received).isEmpty();
    }
  }

  @Test
  void aFullQueueDropsInsteadOfBlocking() throws Exception {
    PushProperties p = PushProperties.defaults().insecureForTests();
    PushProperties tiny = new PushProperties(true, p.vapid(), p.allowedEndpointHosts(), true, false, 1, 1,
        p.connectTimeout(), p.requestTimeout(), 1, 10);
    try (PushKit t = new PushKit(tiny)) {
      java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
      assertThat(t.dispatcher.execute(() -> {
        try {
          gate.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      })).isTrue();
      Thread.sleep(100);                                          // the worker is busy
      assertThat(t.dispatcher.execute(() -> { })).as("queued").isTrue();
      assertThat(t.dispatcher.execute(() -> { })).as("queue full").isFalse();
      assertThat(t.dispatcher.droppedCount()).isEqualTo(1);
      gate.countDown();
    }
  }
}
