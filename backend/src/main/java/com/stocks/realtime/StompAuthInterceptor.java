package com.stocks.realtime;

import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.realtime.StompDestinations.Kind;
import com.stocks.realtime.StompDestinations.Parsed;
import com.stocks.realtime.SubscriptionTracker.ConnectResult;
import com.stocks.realtime.SubscriptionTracker.SubscribeResult;
import java.security.Principal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Server-side controls of the STOMP endpoint (§19), on the client inbound channel after the CSRF check:
 * <ul>
 *   <li><b>CONNECT</b>: the handshake must have been authenticated (chain 3 lets only a signed-in session reach
 *       {@code /ws}) and the player may hold at most {@value SubscriptionTracker#MAX_SESSIONS_PER_PLAYER} sessions.</li>
 *   <li><b>SUBSCRIBE</b>: only the §19 destinations ({@link StompDestinations}). User queues are always the caller's
 *       own ({@code /user/queue/*}, resolved per session by Spring's user-destination handler); a direct
 *       {@code /queue/…} or any unknown destination is refused, and {@code /user/queue/admin} needs
 *       {@code ROLE_ADMIN} (an admin on a session). A non-canonical symbol or the 61st market subscription of a
 *       session is dropped silently (logged), so one bad watchlist row cannot kill the socket.</li>
 *   <li><b>UNSUBSCRIBE / DISCONNECT</b>: release leases ({@link SubscriptionTracker}).</li>
 *   <li><b>SEND</b>: refused; Phase 1 has no client-to-server application destinations.</li>
 * </ul>
 * A refusal is an {@code ERROR} frame whose {@code message} starts with the §7.2 code ({@link StompErrors}), and the
 * connection closes.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

  private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

  /** The authority of an admin on a session ({@code PlayerAuthenticationToken.ROLE_ADMIN}). */
  static final String ROLE_ADMIN = "ROLE_ADMIN";

  private final SubscriptionTracker tracker;
  private final QuoteFanout fanout;
  private final StompErrors errors;

  public StompAuthInterceptor(SubscriptionTracker tracker, QuoteFanout fanout, StompErrors errors) {
    this.tracker = tracker;
    this.fanout = fanout;
    this.errors = errors;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
    if (type == null) {
      return message;
    }
    String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
    return switch (type) {
      case CONNECT -> connect(message, sessionId);
      case SUBSCRIBE -> subscribe(message, sessionId);
      case UNSUBSCRIBE -> {
        String subscriptionId = SimpMessageHeaderAccessor.getSubscriptionId(message.getHeaders());
        if (sessionId != null && subscriptionId != null) {
          tracker.unsubscribe(sessionId, subscriptionId);
        }
        yield message;
      }
      case DISCONNECT -> {
        if (sessionId != null) {
          tracker.disconnect(sessionId);
        }
        yield message;
      }
      case MESSAGE -> errors.reject(message, "FORBIDDEN: clients cannot SEND in Phase 1");
      default -> message;   // heartbeats and other frames
    };
  }

  private Message<?> connect(Message<?> message, String sessionId) {
    Optional<Authentication> auth = authentication(message);
    if (auth.isEmpty()) {
      return errors.reject(message, "UNAUTHENTICATED: sign in before connecting to /ws");
    }
    if (sessionId == null) {
      return errors.reject(message, "VALIDATION_FAILED: no session id");
    }
    PlayerPrincipal player = (PlayerPrincipal) auth.get().getPrincipal();
    ConnectResult result = tracker.connect(sessionId, player.playerId(), auth.get().getName(), isAdmin(auth.get()));
    if (result == ConnectResult.TOO_MANY_SESSIONS) {
      return errors.reject(message, "RATE_LIMITED: at most " + SubscriptionTracker.MAX_SESSIONS_PER_PLAYER
          + " realtime connections per player");
    }
    return message;
  }

  private Message<?> subscribe(Message<?> message, String sessionId) {
    String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
    String subscriptionId = SimpMessageHeaderAccessor.getSubscriptionId(message.getHeaders());
    Optional<Authentication> auth = authentication(message);
    if (auth.isEmpty()) {
      return errors.reject(message, "UNAUTHENTICATED: sign in before subscribing");
    }
    Optional<Parsed> parsed = StompDestinations.parse(destination);
    if (parsed.isEmpty()) {
      return errors.reject(message, "FORBIDDEN: unknown destination " + clip(destination));
    }
    Parsed p = parsed.get();
    if (p.kind() == Kind.ADMIN_QUEUE && !isAdmin(auth.get())) {
      return errors.reject(message, "FORBIDDEN: " + destination + " is for admins");
    }
    if (subscriptionId == null || sessionId == null) {
      return errors.reject(message, "VALIDATION_FAILED: SUBSCRIBE needs an id");
    }
    if (StompDestinations.invalidSymbol(p)) {
      log.debug("STOMP {}: ignoring SUBSCRIBE to {} (not a canonical symbol)", sessionId, clip(destination));
      return null;
    }
    SubscribeResult result = tracker.subscribe(sessionId, subscriptionId, p);
    return switch (result) {
      case OK -> {
        if (p.kind().isMarket()) {
          fanout.prime(p.symbol());
        }
        yield message;
      }
      case CAP_REACHED -> {
        log.info("STOMP {}: ignoring SUBSCRIBE to {}: {} market subscriptions per session", sessionId,
            destination, SubscriptionTracker.MAX_MARKET_SUBSCRIPTIONS_PER_SESSION);
        yield null;
      }
      case DUPLICATE_ID -> errors.reject(message,
          "VALIDATION_FAILED: subscription id " + clip(subscriptionId) + " is already in use");
      case UNKNOWN_SESSION -> errors.reject(message, "UNAUTHENTICATED: CONNECT first");
    };
  }

  private static Optional<Authentication> authentication(Message<?> message) {
    Principal user = SimpMessageHeaderAccessor.getUser(message.getHeaders());
    if (user instanceof Authentication auth && auth.isAuthenticated()
        && auth.getPrincipal() instanceof PlayerPrincipal) {
      return Optional.of(auth);
    }
    return Optional.empty();
  }

  static boolean isAdmin(Authentication auth) {
    return auth.getAuthorities().stream().anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()));
  }

  private static String clip(String s) {
    if (s == null) {
      return "null";
    }
    return s.length() <= 80 ? s : s.substring(0, 80) + "…";
  }
}
