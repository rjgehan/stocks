package com.stocks.push;

import com.stocks.common.domain.NotificationKind;
import com.stocks.common.domain.PushCategory;
import com.stocks.common.web.Text;
import com.stocks.contract.realtime.dto.NotificationDto;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * One Web Push message (§19.2): what the service worker shows, and nothing more. The encrypted payload is
 * {@code {v: 1, kind, title, body, url, tag}}: the title and body the in-app notification shows (body clipped to
 * {@value #BODY_MAX} characters), a same-origin path to open and a tag that collapses repeats. No balances beyond the
 * text, no ids of other players, no endpoint data.
 *
 * @param category push preference category it belongs to
 * @param ttl      how long the push service may hold it for an offline device
 * @param urgency  RFC 8030 {@code Urgency}
 * @param topic    RFC 8030 {@code Topic} (replaces an undelivered message with the same topic), or null
 */
public record PushMessage(PushCategory category, String kind, String title, String body, String url, String tag,
                          Duration ttl, String urgency, String topic) {

  public static final int TITLE_MAX = 120;
  public static final int BODY_MAX = 240;
  static final Duration TTL_ALERT = Duration.ofHours(1);
  static final Duration TTL_DEFAULT = Duration.ofHours(24);
  private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9._\\-]{1,64}$");

  public PushMessage {
    title = Text.clip(title == null ? "stocks" : title, TITLE_MAX);
    body = Text.clip(body == null ? "" : body, BODY_MAX);
    url = url == null || !url.startsWith("/") || url.startsWith("//") ? "/" : url;
  }

  /** The message for an in-app notification. */
  public static PushMessage of(NotificationDto n) {
    NotificationKind kind = n.kind() == null ? NotificationKind.SYSTEM : n.kind();
    PushCategory category = PushCategory.of(kind);
    Map<String, Object> p = n.payload() == null ? Map.of() : n.payload();
    String url;
    String tag;
    switch (kind) {
      case PRICE_ALERT -> {
        String symbol = segment(p.get("symbol"));
        url = symbol == null ? "/alerts" : "/stock/" + symbol;
        String alertId = segment(p.get("alertId"));
        tag = alertId != null ? "alert:" + alertId : n.id();
      }
      case FILL, PARTIAL_FILL, ORDER_CANCELLED, ORDER_EXPIRED -> {
        String orderId = segment(p.get("orderId"));
        url = orderId == null ? "/account/orders" : "/account/orders/" + orderId;
        tag = orderId != null ? "order:" + orderId : n.id();
      }
      case DEPOSIT, WITHDRAWAL, ADJUSTMENT, CORPORATE_ACTION -> {
        url = "/account/activity";
        tag = n.id();
      }
      default -> {
        url = "/account";
        tag = n.id();
      }
    }
    boolean urgent = category == PushCategory.FILLS || category == PushCategory.ALERTS;
    return new PushMessage(category, kind.name(), n.title(), n.body(), url, tag,
        category == PushCategory.ALERTS ? TTL_ALERT : TTL_DEFAULT, urgent ? "high" : "normal", null);
  }

  /** A league event (not an in-app notification), e.g. a new weekly leader. */
  public static PushMessage league(String title, String body, String tag) {
    return new PushMessage(PushCategory.LEAGUE, "LEAGUE", title, body, "/league", tag, TTL_DEFAULT, "normal", null);
  }

  /** {@code POST /me/push/test}. */
  public static PushMessage test() {
    return new PushMessage(PushCategory.ACCOUNT, "TEST", "Test notification",
        "Push notifications work on this device.", "/settings/notifications", "test", Duration.ofMinutes(10),
        "normal", "test");
  }

  /** The JSON the service worker reads, UTF-8. */
  public byte[] payload(JsonMapper mapper) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("v", 1);
    m.put("kind", kind);
    m.put("title", title);
    m.put("body", body);
    m.put("url", url);
    m.put("tag", tag);
    return mapper.writeValueAsString(m).getBytes(StandardCharsets.UTF_8);
  }

  private static String segment(Object value) {
    if (value == null) {
      return null;
    }
    String s = value.toString();
    return SAFE_SEGMENT.matcher(s).matches() ? s : null;
  }
}
