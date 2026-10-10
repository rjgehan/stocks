package com.stocks.realtime;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;

/**
 * Sends to a player's {@code /user/queue/*} (§19). The STOMP user name is the player's public id
 * ({@code PlayerAuthenticationToken.getName()}); {@link SubscriptionTracker} knows it for every connected player, so a
 * push to someone who is not connected is skipped without a lookup. Spring's user-destination handler delivers to
 * every session of that user and only to them, so one player's events never reach another.
 */
@Component
public class UserPush {

  private static final Logger log = LoggerFactory.getLogger(UserPush.class);

  private final SubscriptionTracker tracker;
  private final ObjectProvider<SimpMessageSendingOperations> messaging;

  public UserPush(SubscriptionTracker tracker, ObjectProvider<SimpMessageSendingOperations> messaging) {
    this.tracker = tracker;
    this.messaging = messaging;
  }

  /** True when the player has at least one STOMP session (pushes to anyone else are skipped). */
  public boolean isConnected(long playerId) {
    return tracker.isConnected(playerId);
  }

  /**
   * Sends {@code payload} to {@code queue} ({@code /queue/orders}, …) of a connected player; returns false when the
   * player is not connected or the send failed (best effort: clients resync on reconnect, §19).
   */
  public boolean toPlayer(long playerId, String queue, Object payload) {
    Optional<String> user = tracker.userOf(playerId);
    if (user.isEmpty()) {
      return false;
    }
    SimpMessageSendingOperations ops = messaging.getIfAvailable();
    if (ops == null) {
      return false;
    }
    try {
      ops.convertAndSendToUser(user.get(), queue, payload);
      return true;
    } catch (RuntimeException e) {
      log.warn("Push to player {} {} failed: {}", playerId, queue, e.toString());
      return false;
    }
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
