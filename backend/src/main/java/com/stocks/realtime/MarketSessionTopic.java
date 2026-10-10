package com.stocks.realtime;

import com.stocks.common.error.ApiException;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionChanged;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.jobs.PeriodicTask;
import com.stocks.contract.realtime.dto.MarketSessionDto;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@code /topic/market.session} (§19): {@code MarketSessionDto} on every session change and at least every
 * {@link #HEARTBEAT}. A change is pushed at once when the engine publishes {@code SessionChanged} (a session
 * transition, §9.4); this {@link PeriodicTask} also compares the calendar's state every {@link #CHECK_EVERY}, so a
 * transition is never missed, and re-sends the state every minute while anyone subscribes. Off when
 * {@code stocks.jobs.enabled=false} (then only {@code SessionChanged} pushes).
 */
@Component
public class MarketSessionTopic implements PeriodicTask {

  private static final Logger log = LoggerFactory.getLogger(MarketSessionTopic.class);

  public static final Duration CHECK_EVERY = Duration.ofSeconds(5);
  public static final Duration HEARTBEAT = Duration.ofSeconds(60);

  private final MarketCalendar calendar;
  private final TimeSource time;
  private final SubscriptionTracker tracker;
  private final UserPush push;
  private volatile MarketSessionDto last;
  private volatile Instant lastSentAt;

  public MarketSessionTopic(MarketCalendar calendar, TimeSource time, SubscriptionTracker tracker, UserPush push) {
    this.calendar = calendar;
    this.time = time;
    this.tracker = tracker;
    this.push = push;
  }

  @Override
  public String name() {
    return "STOMP_MARKET_SESSION";
  }

  @Override
  public Duration interval() {
    return CHECK_EVERY;
  }

  @Override
  public boolean sessionsOnly() {
    return false;
  }

  @Override
  public void tick() {
    if (tracker.subscribers(StompDestinations.SESSION_TOPIC) == 0) {
      return;
    }
    Instant now = time.now();
    current(now).ifPresent(dto -> {
      boolean changed = !dto.equals(last);
      boolean stale = lastSentAt == null || !now.isBefore(lastSentAt.plus(HEARTBEAT));
      if (changed || stale) {
        send(dto, now);
      }
    });
  }

  /** The engine's transition event (§9.4): push the new state at once. */
  @EventListener
  public void on(SessionChanged event) {
    try {
      Instant now = time.now();
      Optional<MarketSessionDto> dto = event == null || event.state() == null ? current(now)
          : Optional.of(of(event.state()));
      dto.ifPresent(d -> send(d, now));
    } catch (RuntimeException e) {
      log.warn("market.session push failed: {}", e.toString());
    }
  }

  private void send(MarketSessionDto dto, Instant now) {
    last = dto;
    lastSentAt = now;
    push.toTopic(StompDestinations.SESSION_TOPIC, dto);
  }

  /** The state at {@code now}; empty while the calendar is a Day-0 stub. */
  Optional<MarketSessionDto> current(Instant now) {
    try {
      return Optional.of(of(calendar.stateAt(now)));
    } catch (ApiException e) {
      return Optional.empty();
    }
  }

  MarketSessionDto of(SessionState s) {
    boolean earlyClose = false;
    if (s.tradingDate() != null) {
      try {
        earlyClose = calendar.day(s.tradingDate()).map(TradingDay::earlyClose).orElse(false);
      } catch (ApiException e) {
        earlyClose = false;
      }
    }
    return new MarketSessionDto(Objects.requireNonNull(s.type(), "type"), s.tradingDate(), s.endsAt(), s.nextType(),
        s.nextStartsAt(), earlyClose);
  }
}
