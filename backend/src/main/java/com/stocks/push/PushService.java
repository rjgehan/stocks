package com.stocks.push;

import com.stocks.common.domain.PushCategory;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.SessionIdChanged;
import com.stocks.common.events.SessionsRevoked;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.push.dto.PushDeviceDto;
import com.stocks.contract.push.dto.PushPreferencesDto;
import com.stocks.contract.push.dto.PushSettingsDto;
import com.stocks.contract.push.dto.PushSubscriptionRequest;
import com.stocks.contract.push.dto.PushSyncDto;
import com.stocks.contract.push.dto.PushTestResultDto;
import com.stocks.contract.push.dto.UpdatePushPreferencesRequest;
import com.stocks.contract.realtime.NotificationCreated;
import com.stocks.push.PushDispatcher.Delivery;
import com.stocks.push.PushSubscriptions.Subscription;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Web Push for players (§19.2): settings, devices and preferences ({@code /me/push}), and the fan-out of every new
 * notification to the player's devices.
 *
 * <ul>
 *   <li>{@link NotificationCreated} (published by {@code NotificationService} after its insert) only hands the work
 *       to {@link PushDispatcher}; reading devices and preferences and sending happen on its pool, never on the
 *       publishing (engine, request or realtime) thread.</li>
 *   <li>Subscriptions follow the browser session that registered them: logout deletes that session's devices
 *       ({@link SessionsRevoked} for one session); a password change, admin password reset, freeze, disable or role
 *       change deletes all of the player's devices ({@link SessionsRevoked} for every session); a rotated session id
 *       re-binds them ({@link SessionIdChanged}).</li>
 * </ul>
 */
@Service
public class PushService {

  private static final Logger log = LoggerFactory.getLogger(PushService.class);

  static final Duration TEST_INTERVAL = Duration.ofSeconds(10);
  static final Duration TEST_WAIT = Duration.ofSeconds(15);
  static final int AUTH_LENGTH = 16;

  private final PushSubscriptions subscriptions;
  private final PushDispatcher dispatcher;
  private final VapidKeys keys;
  private final PublicId publicId;
  private final TimeSource time;
  private final PushProperties props;
  private final EndpointPolicy policy;
  private final Map<Long, Instant> lastTest = new ConcurrentHashMap<>();

  public PushService(PushSubscriptions subscriptions, PushDispatcher dispatcher, VapidKeys keys, PublicId publicId,
                     TimeSource time, PushProperties props) {
    this.subscriptions = subscriptions;
    this.dispatcher = dispatcher;
    this.keys = keys;
    this.publicId = publicId;
    this.time = time;
    this.props = props;
    this.policy = EndpointPolicy.of(props);
  }

  /** Whether Web Push works on this server (enabled and a usable VAPID key). */
  public boolean available() {
    return props.enabled() && keys.current().isPresent();
  }

  private void requireAvailable() {
    if (!available()) {
      throw ApiException.of(ErrorCode.PUSH_UNAVAILABLE, "Push notifications are not available on this server.")
          .withHint("Notifications still show in the app.");
    }
  }

  // ------------------------------------------------------------------------------------------- settings

  /** {@code GET /me/push}. {@code sessionHashOrNull} marks the caller's own device ({@code current}). */
  public PushSettingsDto settings(long playerId, String sessionHashOrNull) {
    boolean available = available();
    return new PushSettingsDto(available, available ? keys.current().get().publicKeyB64() : null,
        preferences(playerId), devices(playerId, sessionHashOrNull));
  }

  public PushPreferencesDto preferences(long playerId) {
    Map<PushCategory, Boolean> p = subscriptions.preferences(playerId);
    return new PushPreferencesDto(p.get(PushCategory.FILLS), p.get(PushCategory.ALERTS), p.get(PushCategory.MONEY),
        p.get(PushCategory.ACCOUNT), p.get(PushCategory.LEAGUE));
  }

  /** {@code PATCH /me/push/preferences}: null members stay as they are. */
  public PushPreferencesDto updatePreferences(long playerId, UpdatePushPreferencesRequest req) {
    requireAvailable();
    if (req != null) {
      Instant now = time.now();
      set(playerId, PushCategory.FILLS, req.fills(), now);
      set(playerId, PushCategory.ALERTS, req.alerts(), now);
      set(playerId, PushCategory.MONEY, req.money(), now);
      set(playerId, PushCategory.ACCOUNT, req.account(), now);
      set(playerId, PushCategory.LEAGUE, req.league(), now);
    }
    return preferences(playerId);
  }

  private void set(long playerId, PushCategory c, Boolean value, Instant now) {
    if (value != null) {
      subscriptions.setPreference(playerId, c, value, now);
    }
  }

  private List<PushDeviceDto> devices(long playerId, String sessionHashOrNull) {
    return subscriptions.forPlayer(playerId).stream().map(s -> device(s, sessionHashOrNull)).toList();
  }

  static PushDeviceDto device(Subscription s, String sessionHashOrNull) {
    return new PushDeviceDto(s.publicId(), s.label(), EndpointPolicy.serviceHost(s.endpoint()),
        sessionHashOrNull != null && sessionHashOrNull.equals(s.sessionHash()), s.createdAt(), s.lastSuccessAt(),
        s.lastFailureAt());
  }

  // ------------------------------------------------------------------------------------------- devices

  /**
   * {@code POST /me/push/subscriptions}: registers this browser's subscription for the caller (moving it from another
   * player if it was theirs), keeps at most {@code max-devices-per-player} devices, and returns it.
   */
  public PushDeviceDto subscribe(long playerId, String sessionHashOrNull, PushSubscriptionRequest req,
                                 String userAgent) {
    requireAvailable();
    if (req == null) {
      throw ApiException.validation("endpoint", "endpoint is required.");
    }
    String endpoint = policy.require(req.endpoint()).toString();
    String p256dh = validKey(req.p256dh());
    String auth = validAuth(req.auth());
    Optional<Subscription> before = subscriptions.byEndpoint(endpoint);
    Subscription s = subscriptions.upsert(publicId.next(Prefix.PUSH_SUBSCRIPTION), playerId, endpoint, p256dh, auth,
        sessionHashOrNull, DeviceLabels.label(userAgent), time.now());
    if (before.isPresent() && before.get().playerId() != playerId) {
      log.info("Push device {} moved from player {} to player {}", s.publicId(), before.get().playerId(), playerId);
    }
    int dropped = subscriptions.trim(playerId, Math.max(1, props.maxDevicesPerPlayer()));
    if (dropped > 0) {
      log.info("Dropped {} old push device(s) of player {}", dropped, playerId);
    }
    return device(s, sessionHashOrNull);
  }

  /**
   * {@code POST /me/push/sync}: is this browser's subscription registered for the caller? If so it is re-bound to the
   * current session; another player's registration of it is deleted (the device changed hands) and the answer is
   * {@code registered: false}, so the SPA unsubscribes the browser.
   */
  public PushSyncDto sync(long playerId, String sessionHashOrNull, String endpoint) {
    requireAvailable();
    if (endpoint == null || endpoint.isBlank()) {
      throw ApiException.validation("endpoint", "endpoint is required.");
    }
    Optional<Subscription> s = subscriptions.byEndpoint(endpoint.strip());
    if (s.isEmpty()) {
      return new PushSyncDto(false, null);
    }
    if (s.get().playerId() != playerId) {
      subscriptions.delete(s.get().id());
      log.info("Push device {} of player {} unregistered: another player signed in on it", s.get().publicId(),
          s.get().playerId());
      return new PushSyncDto(false, null);
    }
    if (sessionHashOrNull != null && !sessionHashOrNull.equals(s.get().sessionHash())) {
      subscriptions.bindSession(s.get().id(), sessionHashOrNull, time.now());
    }
    Subscription fresh = subscriptions.byId(s.get().id()).orElse(s.get());
    return new PushSyncDto(true, device(fresh, sessionHashOrNull));
  }

  /** {@code POST /me/push/unsubscribe}: forgets this browser's subscription (only the caller's). */
  public void unsubscribe(long playerId, String endpoint) {
    if (endpoint != null && !endpoint.isBlank()) {
      subscriptions.deleteEndpoint(playerId, endpoint.strip());
    }
  }

  /** {@code DELETE /me/push/subscriptions/{deviceId}}. */
  public void deleteDevice(long playerId, String deviceId) {
    if (deviceId == null || subscriptions.delete(playerId, deviceId.strip()) == 0) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "No such device.");
    }
  }

  /**
   * {@code POST /me/push/test}: "Test notification" to every device of the caller, waiting up to 15 s for the push
   * services. At most one call per 10 s per player.
   */
  public PushTestResultDto test(long playerId) {
    requireAvailable();
    Instant now = time.now();
    Instant last = lastTest.get(playerId);
    if (last != null && Duration.between(last, now).compareTo(TEST_INTERVAL) < 0) {
      long wait = Math.max(1, TEST_INTERVAL.minus(Duration.between(last, now)).toSeconds());
      throw ApiException.of(ErrorCode.RATE_LIMITED, "One test notification per 10 seconds.").withRetryAfter(wait);
    }
    lastTest.put(playerId, now);
    List<Subscription> devices = subscriptions.forPlayer(playerId);
    PushMessage message = PushMessage.test();
    List<CompletableFuture<Delivery>> futures = new ArrayList<>();
    for (Subscription s : devices) {
      futures.add(dispatcher.deliver(s, message));
    }
    int delivered = 0;
    int failed = 0;
    int removed = 0;
    long deadline = System.nanoTime() + TEST_WAIT.toNanos();
    for (CompletableFuture<Delivery> f : futures) {
      Delivery d;
      try {
        d = f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      } catch (TimeoutException | ExecutionException e) {
        d = Delivery.FAILED;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        d = Delivery.FAILED;
      }
      switch (d) {
        case DELIVERED -> delivered++;
        case REMOVED -> removed++;
        default -> failed++;
      }
    }
    return new PushTestResultDto(devices.size(), delivered, failed, removed);
  }

  // ------------------------------------------------------------------------------------------- fan-out

  /** A new notification: queue its push (the listener itself never blocks). */
  @EventListener
  public void onNotification(NotificationCreated e) {
    if (e == null || e.notification() == null || !available()) {
      return;
    }
    PushMessage message;
    try {
      message = PushMessage.of(e.notification());
    } catch (RuntimeException ex) {
      log.warn("Push message for notification {} could not be built: {}", e.notification().id(), ex.toString());
      return;
    }
    dispatcher.execute(() -> sendToPlayer(e.playerId(), message));
  }

  /** Sends {@code message} to every device of the player, if the player wants its category. */
  void sendToPlayer(long playerId, PushMessage message) {
    if (!subscriptions.wants(playerId, message.category())) {
      return;
    }
    for (Subscription s : subscriptions.forPlayer(playerId)) {
      dispatcher.deliver(s, message);
    }
  }

  /** A league event for every player who wants {@link PushCategory#LEAGUE}; returns how many players. */
  public int sendLeague(PushMessage message) {
    if (!available()) {
      return 0;
    }
    List<Long> players = subscriptions.playersWanting(PushCategory.LEAGUE);
    for (long player : players) {
      for (Subscription s : subscriptions.forPlayer(player)) {
        dispatcher.deliver(s, message);
      }
    }
    return players.size();
  }

  // ------------------------------------------------------------------------------------------- sessions

  /** Logout deletes the devices that session registered; every-session revocations delete all of the player's. */
  @EventListener
  public void onSessionsRevoked(SessionsRevoked e) {
    try {
      int n;
      if (e.sessionIdOrNull() != null) {
        n = subscriptions.deleteBySession(PushSubscriptions.sha256(e.sessionIdOrNull()));
      } else if (e.playerPublicId() != null) {
        n = subscriptions.deleteByPlayerPublicId(e.playerPublicId());
      } else {
        n = 0;
      }
      if (n > 0) {
        log.info("Removed {} push device(s) of ended session(s) (player {})", n, e.playerPublicId());
      }
    } catch (RuntimeException ex) {
      log.warn("Removing push devices of ended sessions failed: {}", ex.toString());
    }
  }

  @EventListener
  public void onSessionIdChanged(SessionIdChanged e) {
    if (e.oldSessionId() == null || e.newSessionId() == null) {
      return;
    }
    try {
      subscriptions.rebindSession(PushSubscriptions.sha256(e.oldSessionId()),
          PushSubscriptions.sha256(e.newSessionId()));
    } catch (RuntimeException ex) {
      log.warn("Re-binding push devices to a new session id failed: {}", ex.toString());
    }
  }

  // ------------------------------------------------------------------------------------------- validation

  private static String validKey(String p256dh) {
    try {
      byte[] point = WebPushCrypto.unb64(p256dh == null ? "" : p256dh);
      return WebPushCrypto.b64(WebPushCrypto.encodePoint(WebPushCrypto.decodePoint(point)));
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("p256dh", "p256dh must be the browser's P-256 public key (base64url).");
    }
  }

  private static String validAuth(String auth) {
    try {
      byte[] secret = WebPushCrypto.unb64(auth == null ? "" : auth);
      if (secret.length != AUTH_LENGTH) {
        throw new IllegalArgumentException("auth length");
      }
      return WebPushCrypto.b64(secret);
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("auth", "auth must be the subscription's 16-byte secret (base64url).");
    }
  }
}
