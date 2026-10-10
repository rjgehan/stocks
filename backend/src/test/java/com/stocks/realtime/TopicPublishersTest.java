package com.stocks.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.SessionType;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionChanged;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.realtime.dto.MarketSessionDto;
import com.stocks.realtime.RealtimeTestSupport.CapturingMessaging;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §19 broadcast topics: the market.session publisher and the periodic account push. ({@code /topic/leaderboard} and
 * {@code /topic/feed} are published by WS4's {@code LeaderboardServiceImpl} and {@code FeedService}.)
 */
class TopicPublishersTest {

  MutableTimeSource time;
  CapturingMessaging messaging;
  SubscriptionTracker tracker;
  UserPush push;

  @BeforeEach
  void setUp() {
    time = TestTimes.clock();
    messaging = new CapturingMessaging();
    tracker = new SubscriptionTracker(new RecordingMarketData(time), time);
    push = new UserPush(tracker, RealtimeTestSupport.provider(messaging));
    tracker.connect("s1", 1, "ply_1", false);
  }

  void subscribe(String id, String destination) {
    tracker.subscribe("s1", id, StompDestinations.parse(destination).orElseThrow());
  }

  // ------------------------------------------------------------------------------------------- market.session

  @Test
  void sessionTopicSendsOnChangeAndEveryMinute() {
    MarketCalendar calendar = mock(MarketCalendar.class);
    SessionState regular = new SessionState(SessionType.REGULAR, TestTimes.TRADE_DATE, TestTimes.START,
        Instant.parse("2026-10-09T20:00:00Z"), SessionType.POST, Instant.parse("2026-10-09T20:00:00Z"));
    when(calendar.stateAt(any())).thenReturn(regular);
    when(calendar.day(TestTimes.TRADE_DATE)).thenReturn(Optional.of(new TradingDay(TestTimes.TRADE_DATE,
        Instant.parse("2026-10-09T08:00:00Z"), Instant.parse("2026-10-09T13:30:00Z"),
        Instant.parse("2026-10-09T20:00:00Z"), Instant.parse("2026-10-10T00:00:00Z"), TestTimes.SETTLE_DATE, false,
        "BUNDLED")));
    MarketSessionTopic topic = new MarketSessionTopic(calendar, time, tracker, push);

    topic.tick();
    assertThat(messaging.sent).as("nobody subscribes").isEmpty();

    subscribe("m", "/topic/market.session");
    topic.tick();
    topic.tick();
    assertThat(messaging.to("/topic/market.session")).hasSize(1);
    MarketSessionDto dto = (MarketSessionDto) messaging.to("/topic/market.session").get(0).payload();
    assertThat(dto.session()).isEqualTo(SessionType.REGULAR);
    assertThat(dto.nextSession()).isEqualTo(SessionType.POST);
    assertThat(dto.earlyClose()).isFalse();

    time.advance(Duration.ofSeconds(60));
    topic.tick();
    assertThat(messaging.to("/topic/market.session")).as("heartbeat").hasSize(2);

    topic.on(new SessionChanged(new SessionState(SessionType.POST, TestTimes.TRADE_DATE,
        Instant.parse("2026-10-09T20:00:00Z"), Instant.parse("2026-10-10T00:00:00Z"), SessionType.CLOSED,
        Instant.parse("2026-10-10T00:00:00Z"))));
    assertThat(messaging.to("/topic/market.session")).hasSize(3);
    assertThat(((MarketSessionDto) messaging.to("/topic/market.session").get(2).payload()).session())
        .isEqualTo(SessionType.POST);
  }

  @Test
  void sessionTopicIsSilentWhileTheCalendarIsAStub() {
    subscribe("m", "/topic/market.session");
    MarketSessionTopic topic = new MarketSessionTopic(NotImplementedStub.of(MarketCalendar.class, "WS2"), time,
        tracker, push);
    topic.tick();
    assertThat(messaging.sent).isEmpty();
    assertThat(topic.name()).isEqualTo("STOMP_MARKET_SESSION");
    assertThat(topic.sessionsOnly()).isFalse();
  }

  // ------------------------------------------------------------------------------------------- account marks

  @Test
  void theAccountMarksPushGoesToPlayersSubscribedToTheirAccountQueue() {
    com.stocks.contract.trading.AccountQueries accounts = mock(com.stocks.contract.trading.AccountQueries.class);
    when(accounts.activeAccountId(1L)).thenReturn(7L);
    when(accounts.account(7L)).thenReturn(TradingSamples.account("acc_7", "520.00"));
    UserEventPublisher publisher = new UserEventPublisher(mock(com.stocks.contract.trading.OrderQueries.class),
        accounts, mock(NotificationService.class), push, Runnable::run);
    AccountTopic topic = new AccountTopic(tracker, publisher);
    topic.tick();
    assertThat(messaging.sent).isEmpty();
    subscribe("a", "/user/queue/account");
    topic.tick();
    assertThat(messaging.to("/queue/account")).singleElement().satisfies(s -> assertThat(s.user())
        .isEqualTo("ply_1"));
    assertThat(topic.sessionsOnly()).isTrue();
  }
}
