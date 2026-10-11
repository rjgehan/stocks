package com.stocks.realtime;

import com.stocks.realtime.stream.StreamHub;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.stereotype.Component;

/**
 * Sends to a player's {@code /user/queue/*} (§19). The STOMP user name is the player's public id
 * ({@code PlayerAuthenticationToken.getName()}); {@link SubscriptionTracker} knows it for every connected player, so a
 * push to someone who is not connected is skipped without a lookup. Each push is addressed to one session of that
 * user ({@link #toPlayer}), so one player's events never reach another.
 */
@Component
public class UserPush {

  private static final Logger log = LoggerFactory.getLogger(UserPush.class);

  private final SubscriptionTracker tracker;
  private final ObjectProvider<SimpMessageSendingOperations> messaging;
  private final Supplier<StreamHub> streams;

  @Autowired
  public UserPush(SubscriptionTracker tracker, ObjectProvider<SimpMessageSendingOperations> messaging,
                  ObjectProvider<StreamHub> streams) {
    this.tracker = tracker;
    this.messaging = messaging;
    this.streams = streams::getIfAvailable;
  }

  /** Without the SSE stream (tests of the STOMP side). */
  public UserPush(SubscriptionTracker tracker, ObjectProvider<SimpMessageSendingOperations> messaging) {
    this.tracker = tracker;
    this.messaging = messaging;
    this.streams = () -> null;
  }

  /**
   * True when the player has at least one STOMP session, or an SSE stream wants their private events (an open stream
   * or one closed within the reconnect window, §19.3); pushes to anyone else are skipped.
   */
  public boolean isConnected(long playerId) {
    if (tracker.isConnected(playerId)) {
      return true;
    }
    StreamHub hub = streams.get();
    return hub != null && hub.wantsPrivate(playerId);
  }

  /**
   * Sends {@code payload} to {@code queue} ({@code /queue/orders}, …) of every STOMP session of a connected player;
   * returns false when the player is not connected or every send failed (best effort: clients resync on reconnect,
   * §19).
   *
   * <p>One message per session (the {@code simpSessionId} header narrows Spring's user-destination resolution to that
   * session). With {@code preserveReceiveOrder} on, Spring 7.0's {@code UserDestinationMessageHandler} sends each
   * resolved target through a per-session {@code OrderedMessageChannelDecorator}, and a single message resolved to
   * two or more sessions fails after the first ("Expected mutable SimpMessageHeaderAccessor"): a player with the app
   * open on a phone and a desktop would get order, account and notification pushes on one device only.
   */
  public boolean toPlayer(long playerId, String queue, Object payload) {
    StreamHub hub = streams.get();
    if (hub != null) {
      try {
        hub.publishPrivate(playerId, queue, payload);   // §19.3: the SSE stream gets the same payload
      } catch (RuntimeException e) {
        log.warn("SSE push to player {} {} failed: {}", playerId, queue, e.toString());
      }
    }
    Optional<String> user = tracker.userOf(playerId);
    if (user.isEmpty()) {
      return false;
    }
    SimpMessageSendingOperations ops = messaging.getIfAvailable();
    if (ops == null) {
      return false;
    }
    boolean sent = false;
    for (String sessionId : tracker.sessionsOf(playerId)) {
      try {
        ops.convertAndSendToUser(user.get(), queue, payload, sessionHeaders(sessionId));
        sent = true;
      } catch (RuntimeException e) {
        log.warn("Push to player {} {} (session {}) failed: {}", playerId, queue, sessionId, e.toString());
      }
    }
    return sent;
  }

  /** Headers that address one STOMP session of the user (mutable, as Spring's send path requires). */
  static MessageHeaders sessionHeaders(String sessionId) {
    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    headers.setSessionId(sessionId);
    headers.setLeaveMutable(true);
    return headers.getMessageHeaders();
  }

  /** Broadcast to a topic ({@code /topic/leaderboard}, …); best effort. */
  public void toTopic(String topic, Object payload) {
    SimpMessageSendingOperations ops = messaging.getIfAvailable();
    if (ops == null) {
      return;
    }
    try {
      ops.convertAndSend(topic, payload);
    } catch (RuntimeException e) {
      log.warn("Push to {} failed: {}", topic, e.toString());
    }
  }
}
