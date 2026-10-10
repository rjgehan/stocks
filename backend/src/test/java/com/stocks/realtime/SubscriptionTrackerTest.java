package com.stocks.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.realtime.StompDestinations.Parsed;
import com.stocks.realtime.SubscriptionTracker.ConnectResult;
import com.stocks.realtime.SubscriptionTracker.SubscribeResult;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §19 server-side controls: session and subscription caps, and the leases subscriptions hold (§15.5). */
class SubscriptionTrackerTest {

  MutableTimeSource time;
  RecordingMarketData md;
  SubscriptionTracker tracker;

  @BeforeEach
  void setUp() {
    time = TestTimes.clock();
    md = new RecordingMarketData(time);
    tracker = new SubscriptionTracker(md, time);
  }

  static Parsed dest(String destination) {
    return StompDestinations.parse(destination).orElseThrow();
  }

  @Test
  void atMostFiveSessionsPerPlayer() {
    for (int i = 1; i <= 5; i++) {
      assertThat(tracker.connect("s" + i, 1, "ply_1", false)).isEqualTo(ConnectResult.OK);
    }
    assertThat(tracker.connect("s6", 1, "ply_1", false)).isEqualTo(ConnectResult.TOO_MANY_SESSIONS);
    assertThat(tracker.connect("s1", 1, "ply_1", false)).as("a repeated CONNECT of the same session").isEqualTo(
        ConnectResult.OK);
    assertThat(tracker.connect("other", 2, "ply_2", false)).isEqualTo(ConnectResult.OK);
    tracker.disconnect("s3");
    assertThat(tracker.sessionCount(1)).isEqualTo(4);
    assertThat(tracker.connect("s6", 1, "ply_1", false)).isEqualTo(ConnectResult.OK);
  }

  @Test
  void quoteAndBarsHoldAViewingLeaseAndTicksAListLeaseOnePerSymbolAtTheHighestPriority() {
    tracker.connect("s1", 1, "ply_1", false);
    String owner = SubscriptionTracker.ownerKey("s1");

    assertThat(tracker.subscribe("s1", "t1", dest("/topic/tick.AAPL"))).isEqualTo(SubscribeResult.OK);
    assertThat(md.lease("AAPL", owner).orElseThrow().priority()).isEqualTo(LeasePriority.LIST);

    tracker.subscribe("s1", "q1", dest("/topic/quote.AAPL"));
    assertThat(md.lease("AAPL", owner).orElseThrow().priority()).as("upgraded").isEqualTo(LeasePriority.VIEWING);
    tracker.subscribe("s1", "b1", dest("/topic/bars.AAPL.1Min"));
    assertThat(md.lease("AAPL", owner).orElseThrow().priority()).isEqualTo(LeasePriority.VIEWING);

    tracker.unsubscribe("s1", "q1");
    assertThat(md.lease("AAPL", owner).orElseThrow().priority()).as("bars still view").isEqualTo(
        LeasePriority.VIEWING);
    tracker.unsubscribe("s1", "b1");
    assertThat(md.lease("AAPL", owner).orElseThrow().priority()).as("downgraded").isEqualTo(LeasePriority.LIST);
    tracker.unsubscribe("s1", "t1");
    assertThat(md.lease("AAPL", owner)).as("released").isEmpty();
    assertThat(tracker.watched("AAPL")).isFalse();
  }

  @Test
  void subscriberCountsAreGlobalAndDisconnectReleasesEverything() {
    tracker.connect("s1", 1, "ply_1", false);
    tracker.connect("s2", 2, "ply_2", false);
    tracker.subscribe("s1", "a", dest("/topic/quote.MSFT"));
    tracker.subscribe("s2", "a", dest("/topic/quote.MSFT"));
    tracker.subscribe("s2", "b", dest("/topic/leaderboard"));
    tracker.subscribe("s2", "c", dest("/user/queue/account"));
    assertThat(tracker.subscribers("/topic/quote.MSFT")).isEqualTo(2);
    assertThat(tracker.subscribers("/topic/leaderboard")).isEqualTo(1);
    assertThat(tracker.watched("MSFT")).isTrue();
    assertThat(tracker.watchedSymbols()).containsExactly("MSFT");
    assertThat(tracker.playersSubscribedTo("/user/queue/account")).containsExactly(2L);

    tracker.disconnect("s2");
    tracker.disconnect("s2");   // idempotent
    assertThat(tracker.subscribers("/topic/quote.MSFT")).isEqualTo(1);
    assertThat(tracker.subscribers("/topic/leaderboard")).isZero();
    assertThat(tracker.playersSubscribedTo("/user/queue/account")).isEmpty();
    assertThat(md.lease("MSFT", SubscriptionTracker.ownerKey("s2"))).isEmpty();
    assertThat(md.lease("MSFT", SubscriptionTracker.ownerKey("s1"))).isPresent();
    assertThat(tracker.isConnected(2)).isFalse();
    assertThat(tracker.userOf(1)).hasValue("ply_1");
  }

  @Test
  void sixtyMarketSubscriptionsPerSessionThenCapReached() {
    tracker.connect("s1", 1, "ply_1", false);
    for (int i = 0; i < SubscriptionTracker.MAX_MARKET_SUBSCRIPTIONS_PER_SESSION; i++) {
      String kind = i % 3 == 0 ? "quote" : i % 3 == 1 ? "tick" : "bars";
      String d = kind.equals("bars") ? "/topic/bars.S" + i + ".1Min" : "/topic/" + kind + ".S" + i;
      assertThat(tracker.subscribe("s1", "id" + i, dest(d))).isEqualTo(SubscribeResult.OK);
    }
    assertThat(tracker.subscribe("s1", "x", dest("/topic/quote.ZZZ"))).isEqualTo(SubscribeResult.CAP_REACHED);
    assertThat(tracker.subscribe("s1", "y", dest("/topic/leaderboard"))).as("non-market topics are not capped")
        .isEqualTo(SubscribeResult.OK);
    tracker.unsubscribe("s1", "id0");
    assertThat(tracker.subscribe("s1", "x", dest("/topic/quote.ZZZ"))).isEqualTo(SubscribeResult.OK);
    assertThat(tracker.marketSubscriptions("s1")).isEqualTo(60);
  }

  @Test
  void duplicateIdsAndUnknownSessionsAreRefused() {
    assertThat(tracker.subscribe("nope", "a", dest("/topic/quote.AAPL"))).isEqualTo(
        SubscribeResult.UNKNOWN_SESSION);
    tracker.connect("s1", 1, "ply_1", false);
    tracker.subscribe("s1", "a", dest("/topic/quote.AAPL"));
    assertThat(tracker.subscribe("s1", "a", dest("/topic/quote.MSFT"))).isEqualTo(SubscribeResult.DUPLICATE_ID);
    tracker.unsubscribe("s1", "unknown");   // ignored
    assertThat(tracker.subscribers("/topic/quote.AAPL")).isEqualTo(1);
  }

  @Test
  void botQuoteLeasesLastSixtySecondsAndAreExtendedByUse() {
    tracker.touchBotLeases(42, List.of("AAPL", "SPY"));
    String owner = SubscriptionTracker.botOwnerKey(42);
    assertThat(md.lease("AAPL", owner).orElseThrow().priority()).isEqualTo(LeasePriority.LIST);
    assertThat(tracker.botLeaseKeys()).containsExactlyInAnyOrder("bot:42|AAPL", "bot:42|SPY");

    time.advance(Duration.ofSeconds(40));
    tracker.touchBotLeases(42, List.of("AAPL"));
    time.advance(Duration.ofSeconds(25));
    assertThat(tracker.expireBotLeases()).isEqualTo(1);   // SPY: 65 s old
    assertThat(md.lease("SPY", owner)).isEmpty();
    assertThat(md.lease("AAPL", owner)).isPresent();

    time.advance(Duration.ofSeconds(40));
    assertThat(tracker.expireBotLeases()).isEqualTo(1);
    assertThat(md.openLeases()).isEmpty();
    assertThat(tracker.botLeaseKeys()).isEmpty();
  }
}
