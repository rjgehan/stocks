package com.stocks.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.NotificationKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.SessionIdChanged;
import com.stocks.common.events.SessionsRevoked;
import com.stocks.contract.push.dto.PushDeviceDto;
import com.stocks.contract.push.dto.PushPreferencesDto;
import com.stocks.contract.push.dto.PushSettingsDto;
import com.stocks.contract.push.dto.PushSubscriptionRequest;
import com.stocks.contract.push.dto.PushSyncDto;
import com.stocks.contract.push.dto.PushTestResultDto;
import com.stocks.contract.push.dto.UpdatePushPreferencesRequest;
import com.stocks.contract.realtime.NotificationCreated;
import com.stocks.contract.realtime.dto.NotificationDto;
import com.stocks.push.FakePushService.Browser;
import com.stocks.push.FakePushService.Reply;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §19.2 subscription lifecycle and fan-out (also on PostgreSQL with {@code -Ppg-it}, which runs the MERGEs with and
 * without a session): one player per subscription, sync after sign-in, removal on logout of that session and on
 * every-session revocations (password change, freeze, ...), re-binding on a rotated session id, device limit,
 * validation, preferences, notification fan-out and the test push.
 */
class PushServiceTest {

  static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) Version/17.5 Mobile Safari/604.1";

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

  static String session(String id) {
    return PushSubscriptions.sha256(id);
  }

  PushDeviceDto subscribe(PlayerRef p, Browser b, String sessionId) {
    return k.push.subscribe(p.id(), sessionId == null ? null : session(sessionId),
        new PushSubscriptionRequest(b.endpoint(), b.p256dh(), b.authB64()), UA);
  }

  @Test
  void settingsShowTheKeyThePreferencesAndTheDevices() {
    PushSettingsDto empty = k.push.settings(alice.id(), session("s1"));
    assertThat(empty.available()).isTrue();
    assertThat(empty.vapidPublicKey()).isEqualTo(k.keys.current().orElseThrow().publicKeyB64());
    assertThat(empty.preferences()).isEqualTo(new PushPreferencesDto(true, true, true, true, true));
    assertThat(empty.devices()).isEmpty();

    PushDeviceDto d = subscribe(alice, k.service.subscription(), "s1");
    assertThat(d.id()).startsWith("psh_");
    assertThat(d.label()).isEqualTo("Safari on iPhone");
    assertThat(d.pushService()).isEqualTo("127.0.0.1");
    assertThat(d.current()).isTrue();
    subscribe(alice, k.service.subscription(), "s2");
    assertThat(k.push.settings(alice.id(), session("s1")).devices()).extracting(PushDeviceDto::current)
        .containsExactly(true, false);
    assertThat(k.push.settings(bob.id(), session("s1")).devices()).isEmpty();
  }

  @Test
  void subscriptionsAreValidated() {
    Browser b = k.service.subscription();
    assertThatThrownBy(() -> k.push.subscribe(alice.id(), null, new PushSubscriptionRequest(b.endpoint(), "AAAA",
        b.authB64()), UA)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.fields()).extracting(f -> f.field()).containsExactly("p256dh"));
    assertThatThrownBy(() -> k.push.subscribe(alice.id(), null, new PushSubscriptionRequest(b.endpoint(), b.p256dh(),
        "c2hvcnQ"), UA)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.fields()).extracting(f -> f.field()).containsExactly("auth"));
    try (PushKit strict = newStrict()) {
      PlayerRef carol = strict.fx.human("carol");
      assertThatThrownBy(() -> strict.push.subscribe(carol.id(), null, new PushSubscriptionRequest(
          "https://evil.example.com/push", b.p256dh(), b.authB64()), UA))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
      assertThat(strict.push.subscribe(carol.id(), null, new PushSubscriptionRequest(
          "https://fcm.googleapis.com/fcm/send/abc", b.p256dh(), b.authB64()), UA).pushService())
          .isEqualTo("fcm.googleapis.com");
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static PushKit newStrict() throws java.io.IOException {
    return new PushKit(PushProperties.defaults());
  }

  @Test
  void aSubscriptionBelongsToOnePlayerAndMovesWithTheBrowser() {
    Browser b = k.service.subscription();
    PushDeviceDto mine = subscribe(alice, b, "s1");
    PushDeviceDto moved = subscribe(bob, b, "s2");
    assertThat(moved.id()).isEqualTo(mine.id());
    assertThat(k.subscriptions.forPlayer(alice.id())).isEmpty();
    assertThat(k.subscriptions.forPlayer(bob.id())).hasSize(1);
    // alice signs in on that browser again: sync says it is not hers, and bob loses it (the device changed hands)
    PushSyncDto sync = k.push.sync(alice.id(), session("s3"), b.endpoint());
    assertThat(sync.registered()).isFalse();
    assertThat(sync.device()).isNull();
    assertThat(k.subscriptions.forPlayer(bob.id())).isEmpty();
    // unknown endpoint
    assertThat(k.push.sync(alice.id(), session("s3"), k.service.subscription().endpoint()).registered()).isFalse();
  }

  @Test
  void syncRebindsTheCurrentSession() {
    Browser b = k.service.subscription();
    subscribe(alice, b, "s1");
    PushSyncDto sync = k.push.sync(alice.id(), session("s9"), b.endpoint());
    assertThat(sync.registered()).isTrue();
    assertThat(sync.device().current()).isTrue();
    // logging out of the old session no longer removes it; logging out of the new one does
    k.push.onSessionsRevoked(SessionsRevoked.one(null, "s1"));
    assertThat(k.subscriptions.forPlayer(alice.id())).hasSize(1);
    k.push.onSessionsRevoked(SessionsRevoked.one(null, "s9"));
    assertThat(k.subscriptions.forPlayer(alice.id())).isEmpty();
  }

  @Test
  void logoutRemovesThatSessionsDevicesAndPasswordChangesRemoveAll() {
    subscribe(alice, k.service.subscription(), "phone");
    subscribe(alice, k.service.subscription(), "laptop");
    subscribe(bob, k.service.subscription(), "bob");
    k.push.onSessionsRevoked(SessionsRevoked.one(null, "phone"));
    assertThat(k.subscriptions.forPlayer(alice.id())).extracting(s -> s.sessionHash())
        .containsExactly(session("laptop"));
    // a login rotates the laptop's session id: its device follows
    k.push.onSessionIdChanged(new SessionIdChanged("laptop", "laptop2"));
    assertThat(k.subscriptions.forPlayer(alice.id())).extracting(s -> s.sessionHash())
        .containsExactly(session("laptop2"));
    // a password change (every other session) or a freeze (every session) removes every device of the player
    subscribe(alice, k.service.subscription(), "tablet");
    k.push.onSessionsRevoked(SessionsRevoked.allBut(alice.publicId(), "laptop2"));
    assertThat(k.subscriptions.forPlayer(alice.id())).isEmpty();
    assertThat(k.subscriptions.forPlayer(bob.id())).hasSize(1);
    k.push.onSessionsRevoked(SessionsRevoked.all(bob.publicId()));
    assertThat(k.subscriptions.forPlayer(bob.id())).isEmpty();
  }

  @Test
  void unsubscribeAndDeleteOnlyTouchTheCallersDevices() {
    Browser b = k.service.subscription();
    PushDeviceDto d = subscribe(alice, b, "s1");
    k.push.unsubscribe(bob.id(), b.endpoint());
    assertThat(k.subscriptions.forPlayer(alice.id())).hasSize(1);
    assertThatThrownBy(() -> k.push.deleteDevice(bob.id(), d.id()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    k.push.unsubscribe(alice.id(), b.endpoint());
    assertThat(k.subscriptions.forPlayer(alice.id())).isEmpty();
    PushDeviceDto again = subscribe(alice, b, "s1");
    k.push.deleteDevice(alice.id(), again.id());
    assertThat(k.subscriptions.forPlayer(alice.id())).isEmpty();
  }

  @Test
  void atMostTenDevicesTheOldestGoes() {
    Browser first = k.service.subscription();
    subscribe(alice, first, "s0");
    for (int i = 1; i <= 10; i++) {
      k.time.advance(Duration.ofSeconds(1));
      subscribe(alice, k.service.subscription(), "s" + i);
    }
    assertThat(k.subscriptions.forPlayer(alice.id())).hasSize(10);
    assertThat(k.subscriptions.byEndpoint(first.endpoint())).isEmpty();
  }

  @Test
  void preferencesArePartialAndFilterTheFanOut() throws Exception {
    PushPreferencesDto p = k.push.updatePreferences(alice.id(), new UpdatePushPreferencesRequest(false, null, null,
        null, false));
    assertThat(p).isEqualTo(new PushPreferencesDto(false, true, true, true, false));
    assertThat(k.push.updatePreferences(alice.id(), new UpdatePushPreferencesRequest(true, null, null, null, null)))
        .isEqualTo(new PushPreferencesDto(true, true, true, true, false));
    k.push.updatePreferences(alice.id(), new UpdatePushPreferencesRequest(false, null, null, null, null));

    Browser b = k.service.subscription();
    subscribe(alice, b, "s1");
    k.push.onNotification(new NotificationCreated(alice.id(), new NotificationDto("ntf_1", NotificationKind.FILL,
        "Filled", "Bought 1 AAPL", Map.of("orderId", "ord_1"), TestTimes.START, null)));
    k.push.onNotification(new NotificationCreated(alice.id(), new NotificationDto("ntf_2",
        NotificationKind.PRICE_ALERT, "AAPL ≥ $230.00", "AAPL is at $230.15.", Map.of("symbol", "AAPL",
        "alertId", "alr_1"), TestTimes.START, null)));
    k.service.await(1);
    Thread.sleep(200);                                           // nothing else arrives (fills are off)
    assertThat(k.service.received).hasSize(1);
    String json = b.decrypt(k.service.received.get(0).body());
    assertThat(json).contains("\"kind\":\"PRICE_ALERT\"").contains("\"url\":\"/stock/AAPL\"")
        .contains("\"tag\":\"alert:alr_1\"");
    assertThat(k.service.received.get(0).header("Urgency")).isEqualTo("high");
    assertThat(k.service.received.get(0).header("TTL")).isEqualTo("3600");
    // league pushes go to players who want them
    assertThat(k.push.sendLeague(PushMessage.league("New weekly leader", "Claude leads.", "league:leader")))
        .as("alice turned league off").isZero();
  }

  @Test
  void theTestPushReportsEachDeviceAndIsRateLimited() {
    subscribe(alice, k.service.subscription(), "s1");
    subscribe(alice, k.service.subscription(), "s2");
    k.service.reply(Reply.of(201), Reply.of(410));
    PushTestResultDto r = k.push.test(alice.id());
    assertThat(r).isEqualTo(new PushTestResultDto(2, 1, 0, 1));
    assertThatThrownBy(() -> k.push.test(alice.id())).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
      assertThat(e.retryAfterS()).isEqualTo(10L);
    });
    k.time.advance(Duration.ofSeconds(10));
    assertThat(k.push.test(alice.id())).isEqualTo(new PushTestResultDto(1, 1, 0, 0));
    assertThat(k.push.test(bob.id())).isEqualTo(new PushTestResultDto(0, 0, 0, 0));
  }

  @Test
  void withoutAUsableKeyEveryWriteIsUnavailable() throws Exception {
    PushProperties d = PushProperties.defaults();
    PushProperties off = new PushProperties(false, d.vapid(), d.allowedEndpointHosts(), true, false, 4, 1000,
        d.connectTimeout(), d.requestTimeout(), 3, 10);
    try (PushKit o = new PushKit(off)) {
      PlayerRef carol = o.fx.human("carol");
      PushSettingsDto s = o.push.settings(carol.id(), null);
      assertThat(s.available()).isFalse();
      assertThat(s.vapidPublicKey()).isNull();
      Browser b = o.service.subscription();
      assertThatThrownBy(() -> o.push.subscribe(carol.id(), null, new PushSubscriptionRequest(b.endpoint(),
          b.p256dh(), b.authB64()), UA))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PUSH_UNAVAILABLE));
      assertThatThrownBy(() -> o.push.test(carol.id()))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PUSH_UNAVAILABLE));
      // notifications still work in the app: the listener just does nothing
      o.push.onNotification(new NotificationCreated(carol.id(), new NotificationDto("ntf_1", NotificationKind.FILL,
          "t", "b", null, TestTimes.START, null)));
    }
  }
}
