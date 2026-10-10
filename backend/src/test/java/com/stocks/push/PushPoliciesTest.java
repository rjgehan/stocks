package com.stocks.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.NotificationKind;
import com.stocks.common.domain.PushCategory;
import com.stocks.common.error.ApiException;
import com.stocks.config.JacksonConfig;
import com.stocks.contract.realtime.dto.NotificationDto;
import com.stocks.testkit.TestTimes;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * §19.2 pure pieces: the endpoint allowlist (the SSRF guard), device labels, the message a notification becomes (only
 * what the notification shows, a same-origin URL) and every category mapping.
 */
class PushPoliciesTest {

  static final JsonMapper MAPPER = JacksonConfig.newMapper();
  final EndpointPolicy policy = EndpointPolicy.of(PushProperties.defaults());

  @Test
  void onlyHttpsEndpointsOfKnownPushServicesAreAccepted() {
    for (String ok : new String[] {
        "https://fcm.googleapis.com/fcm/send/abc:APA91b",
        "https://updates.push.services.mozilla.com/wpush/v2/gAAAA",
        "https://web.push.apple.com/QGv7aXfo",
        "https://api.push.apple.com/x",
        "https://wns2-bl2p.notify.windows.com/w/?token=BQYAAAB",
        "https://FCM.googleapis.com:443/fcm/send/abc"}) {
      assertThat(policy.check(ok)).as(ok).isPresent();
    }
    for (String bad : new String[] {
        "http://fcm.googleapis.com/fcm/send/abc",                 // not https
        "https://fcm.googleapis.com:8443/fcm/send/abc",           // not 443
        "https://evil.example.com/push",                          // not a push service
        "https://fcm.googleapis.com.evil.com/x",                  // suffix trick
        "https://push.apple.com/x",                               // *.push.apple.com is subdomains only
        "https://notify.windows.com/x",
        "https://user:pw@fcm.googleapis.com/x",                   // user info
        "https://127.0.0.1/x", "https://[::1]/x", "https://169.254.169.254/latest",
        "https://fcm.googleapis.com/x#frag",
        "fcm.googleapis.com/x", "mailto:a@b", "", "   ",
        "https://fcm.googleapis.com/" + "a".repeat(1100)}) {
      assertThat(policy.check(bad)).as(bad).isEmpty();
    }
    assertThat(policy.check(null)).isEmpty();
    assertThatThrownBy(() -> policy.require("https://evil.example.com/x")).isInstanceOf(ApiException.class)
        .hasMessageContaining("not accepted");
    // tests only: a local fake push service
    EndpointPolicy insecure = new EndpointPolicy(PushProperties.DEFAULT_HOSTS, true);
    assertThat(insecure.check("http://127.0.0.1:18330/push/1")).isPresent();
    assertThat(insecure.check("ftp://127.0.0.1/x")).isEmpty();
    assertThat(EndpointPolicy.serviceHost("https://FCM.googleapis.com/fcm/send/x")).isEqualTo("fcm.googleapis.com");
    assertThat(EndpointPolicy.serviceHost("::")).isEmpty();
  }

  @Test
  void devicesGetAReadableLabel() {
    assertThat(DeviceLabels.label("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
        + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1")).isEqualTo("Safari on iPhone");
    assertThat(DeviceLabels.label("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Chrome/129.0.0.0 Mobile Safari/537.36")).isEqualTo("Chrome on Android");
    assertThat(DeviceLabels.label("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like "
        + "Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0")).isEqualTo("Edge on Mac");
    assertThat(DeviceLabels.label("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"))
        .isEqualTo("Firefox on Windows");
    assertThat(DeviceLabels.label(null)).isEqualTo("Browser");
    assertThat(DeviceLabels.label("curl/8.0")).isEqualTo("Browser");
  }

  static NotificationDto notification(NotificationKind kind, Map<String, Object> payload) {
    return new NotificationDto("ntf_1", kind, "Title", "Body", payload, TestTimes.START, null);
  }

  @Test
  void aNotificationBecomesAMessageWithOnlyWhatItShows() {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("alertId", "alr_01ABC");
    p.put("symbol", "AAPL");
    p.put("price", "230.15");
    PushMessage alert = PushMessage.of(notification(NotificationKind.PRICE_ALERT, p));
    assertThat(alert.category()).isEqualTo(PushCategory.ALERTS);
    assertThat(alert.url()).isEqualTo("/stock/AAPL");
    assertThat(alert.tag()).isEqualTo("alert:alr_01ABC");
    assertThat(alert.ttl()).isEqualTo(Duration.ofHours(1));
    assertThat(alert.urgency()).isEqualTo("high");
    JsonNode json = MAPPER.readTree(new String(alert.payload(MAPPER), StandardCharsets.UTF_8));
    assertThat(json.propertyNames()).containsExactly("v", "kind", "title", "body", "url", "tag");
    assertThat(json.get("kind").asString()).isEqualTo("PRICE_ALERT");

    PushMessage fill = PushMessage.of(notification(NotificationKind.FILL, Map.of("orderId", "ord_01XYZ",
        "symbol", "AAPL")));
    assertThat(fill.category()).isEqualTo(PushCategory.FILLS);
    assertThat(fill.url()).isEqualTo("/account/orders/ord_01XYZ");
    assertThat(fill.ttl()).isEqualTo(Duration.ofHours(24));

    PushMessage deposit = PushMessage.of(notification(NotificationKind.DEPOSIT, null));
    assertThat(deposit.category()).isEqualTo(PushCategory.MONEY);
    assertThat(deposit.url()).isEqualTo("/account/activity");
    assertThat(deposit.urgency()).isEqualTo("normal");

    // a path segment that is not an id never reaches the URL
    PushMessage weird = PushMessage.of(notification(NotificationKind.PRICE_ALERT, Map.of("symbol", "../admin")));
    assertThat(weird.url()).isEqualTo("/alerts");
    // the body is clipped to what a notification can show; an absolute URL is never used
    PushMessage league = new PushMessage(PushCategory.LEAGUE, "LEAGUE", "t", "x".repeat(500), "https://evil/", "tag",
        Duration.ofHours(1), "normal", null);
    assertThat(league.body()).hasSize(PushMessage.BODY_MAX);
    assertThat(league.url()).isEqualTo("/");
    assertThat(PushMessage.test().topic()).isEqualTo("test");
  }

  @Test
  void everyNotificationKindHasACategory() {
    for (NotificationKind k : NotificationKind.values()) {
      assertThat(PushCategory.of(k)).as(k.name()).isNotNull();
      assertThat(PushMessage.of(notification(k, null)).url()).startsWith("/");
    }
    assertThat(PushCategory.of(NotificationKind.GFV_ISSUED)).isEqualTo(PushCategory.ACCOUNT);
    assertThat(PushCategory.of(NotificationKind.ADJUSTMENT)).isEqualTo(PushCategory.MONEY);
  }

  @Test
  void retryAfterIsSecondsOrADateCappedAtFiveMinutes() {
    var now = TestTimes.START;
    assertThat(PushSender.retryAfter("7", now)).isEqualTo(Duration.ofSeconds(7));
    assertThat(PushSender.retryAfter("3600", now)).isEqualTo(Duration.ofMinutes(5));
    assertThat(PushSender.retryAfter("Fri, 09 Oct 2026 14:00:30 GMT", now)).isEqualTo(Duration.ofSeconds(30));
    assertThat(PushSender.retryAfter("Fri, 09 Oct 2026 13:00:00 GMT", now)).isEqualTo(Duration.ZERO);
    assertThat(PushSender.retryAfter("soon", now)).isNull();
    assertThat(PushSender.retryAfter(null, now)).isNull();
    assertThat(PushSender.retryAfter("-1", now)).isNull();
  }
}
