package com.stocks.realtime;

import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.events.PlayerStatusChanged;
import com.stocks.common.events.SessionIdChanged;
import com.stocks.common.events.SessionsRevoked;
import com.stocks.contract.security.PlayerPrincipal;
import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.core.Authentication;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

/**
 * Every open {@code /ws} socket, with the player and the HTTP session that opened it (§19, §17.3). A WebSocket
 * outlives the HTTP session it was opened with unless something closes it, so:
 * <ul>
 *   <li>{@link SessionsRevoked} (password change, logout, freeze, disable, role change, admin password reset) closes
 *       the sockets of the ended sessions with {@code 1008 POLICY_VIOLATION};</li>
 *   <li>{@link PlayerStatusChanged} to FROZEN or DISABLED closes every socket of the player;</li>
 *   <li>a sweep every {@value #SWEEP_SECONDS} s closes sockets whose HTTP session no longer exists (expired or deleted
 *       by any other path), as defence in depth.</li>
 * </ul>
 * The handshake stores the HTTP session id ({@link #handshakeInterceptor()}); {@link SessionIdChanged} re-binds it
 * when a login or password change rotates the id. A closed socket ends its STOMP session, which releases its
 * subscriptions and leases ({@code SessionDisconnectEvent} → {@link SubscriptionTracker#disconnect}).
 */
@Component
public class LiveSockets {

  private static final Logger log = LoggerFactory.getLogger(LiveSockets.class);

  static final long SWEEP_SECONDS = 60;
  /** The WebSocket attribute that holds the HTTP (Spring Session) id, as Spring's handshake interceptor names it. */
  static final String HTTP_SESSION_ID = HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME;
  static final CloseStatus REVOKED = CloseStatus.POLICY_VIOLATION.withReason("session ended");

  private final Map<String, WebSocketSession> open = new ConcurrentHashMap<>();
  private final ObjectProvider<SessionRepository<?>> sessions;
  private final TaskScheduler scheduler;

  public LiveSockets(ObjectProvider<SessionRepository<?>> sessions,
                     @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler) {
    this.sessions = sessions;
    this.scheduler = scheduler;
  }

  /** Copies only the HTTP session id into the socket's attributes (never creates a session). */
  static HttpSessionHandshakeInterceptor handshakeInterceptor() {
    HttpSessionHandshakeInterceptor i = new HttpSessionHandshakeInterceptor(List.of());
    i.setCopyAllAttributes(false);
    i.setCopyHttpSessionId(true);
    i.setCreateSession(false);
    return i;
  }

  /** The transport decorator that registers and unregisters each socket. */
  WebSocketHandler decorate(WebSocketHandler handler) {
    return new WebSocketHandlerDecorator(handler) {
      @Override
      public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        open.put(session.getId(), session);
        super.afterConnectionEstablished(session);
      }

      @Override
      public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        open.remove(session.getId());
        super.afterConnectionClosed(session, closeStatus);
      }
    };
  }

  @EventListener(ApplicationReadyEvent.class)
  public void startSweep() {
    scheduler.scheduleWithFixedDelay(this::sweep, Duration.ofSeconds(SWEEP_SECONDS));
  }

  @EventListener
  public void onRevoked(SessionsRevoked e) {
    int n = closeWhere(s -> e.covers(playerPublicId(s), httpSessionId(s)));
    if (n > 0) {
      log.info("Closed {} realtime connection(s) of ended sessions (player {})", n, e.playerPublicId());
    }
  }

  @EventListener
  public void onStatus(PlayerStatusChanged e) {
    if (e.status() == PlayerStatus.FROZEN || e.status() == PlayerStatus.DISABLED) {
      closeWhere(s -> {
        PlayerPrincipal p = player(s);
        return p != null && p.playerId() == e.playerId();
      });
    }
  }

  @EventListener
  public void onIdChanged(SessionIdChanged e) {
    for (WebSocketSession s : open.values()) {
      if (e.oldSessionId() != null && e.oldSessionId().equals(httpSessionId(s))) {
        s.getAttributes().put(HTTP_SESSION_ID, e.newSessionId());
      }
    }
  }

  /** Closes the sockets whose HTTP session is gone; returns how many. */
  int sweep() {
    SessionRepository<?> repo = sessions.getIfAvailable();
    if (repo == null || open.isEmpty()) {
      return 0;
    }
    try {
      return closeWhere(s -> {
        String id = httpSessionId(s);
        return id != null && repo.findById(id) == null;
      });
    } catch (RuntimeException e) {
      log.warn("Realtime session sweep failed: {}", e.toString());
      return 0;
    }
  }

  /** Number of open sockets (tests, diagnostics). */
  int size() {
    return open.size();
  }

  private int closeWhere(Predicate<WebSocketSession> condition) {
    int n = 0;
    for (WebSocketSession s : List.copyOf(open.values())) {
      if (s.isOpen() && condition.test(s)) {
        try {
          s.close(REVOKED);
        } catch (IOException | RuntimeException e) {
          log.debug("Closing socket {} failed: {}", s.getId(), e.toString());
        }
        open.remove(s.getId());
        n++;
      }
    }
    return n;
  }

  private static String httpSessionId(WebSocketSession s) {
    return s.getAttributes().get(HTTP_SESSION_ID) instanceof String id ? id : null;
  }

  private static PlayerPrincipal player(WebSocketSession s) {
    Principal user = s.getPrincipal();
    return user instanceof Authentication a && a.getPrincipal() instanceof PlayerPrincipal p ? p : null;
  }

  private static String playerPublicId(WebSocketSession s) {
    PlayerPrincipal p = player(s);
    return p != null ? p.playerPublicId() : s.getPrincipal() == null ? null
        : Objects.toString(s.getPrincipal().getName(), null);
  }
}
