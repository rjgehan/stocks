package com.stocks.realtime;

import com.stocks.contract.jobs.PeriodicTask;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The periodic half of {@code /user/queue/account} (§19): "≤ 1 per 5 s while marks move during sessions". Every
 * {@link #EVERY} during PRE, REGULAR and POST, each connected player whose session subscribes to
 * {@code /user/queue/account} gets their live {@code Account} (valued by WS3/WS4 at current marks). Event-driven
 * pushes after fills and transfers come from {@link UserEventPublisher}. Off when {@code stocks.jobs.enabled=false}.
 */
@Component
public class AccountTopic implements PeriodicTask {

  private static final Logger log = LoggerFactory.getLogger(AccountTopic.class);

  public static final Duration EVERY = Duration.ofSeconds(5);
  static final String ACCOUNT_DESTINATION = StompDestinations.USER_PREFIX + StompDestinations.ACCOUNT_QUEUE;

  private final SubscriptionTracker tracker;
  private final UserEventPublisher publisher;

  public AccountTopic(SubscriptionTracker tracker, UserEventPublisher publisher) {
    this.tracker = tracker;
    this.publisher = publisher;
  }

  @Override
  public String name() {
    return "STOMP_ACCOUNT_MARKS";
  }

  @Override
  public Duration interval() {
    return EVERY;
  }

  @Override
  public boolean sessionsOnly() {
    return true;
  }

  @Override
  public void tick() {
    for (Long playerId : tracker.playersSubscribedTo(ACCOUNT_DESTINATION)) {
      try {
        publisher.pushAccountOf(playerId);
      } catch (RuntimeException e) {
        log.debug("Account push to player {} failed: {}", playerId, e.toString());
      }
    }
  }
}
