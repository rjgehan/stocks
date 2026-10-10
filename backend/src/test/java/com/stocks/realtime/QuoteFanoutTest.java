package com.stocks.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.marketdata.dto.BarDto;
import com.stocks.contract.marketdata.dto.QuoteDto;
import com.stocks.contract.marketdata.dto.TickDto;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.realtime.RealtimeTestSupport.CapturingMessaging;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §19 fan-out: {@code onMarketUpdate} only marks a symbol dirty; the flush sends the latest state, at most every
 * 250 ms for quotes and every second for ticks and bars, and only to symbols someone subscribes to.
 */
class QuoteFanoutTest {

  static final long MS = 1_000_000L;

  MutableTimeSource time;
  RecordingMarketData md;
  SubscriptionTracker tracker;
  CapturingMessaging messaging;
  QuoteFanout fanout;
  long t0;

  @BeforeEach
  void setUp() {
    time = TestTimes.clock();
    md = new RecordingMarketData(time);
    md.withAsset("AAPL").prevClose("AAPL", "225.00").quote("AAPL", TestTimes.START, "227.39", 300, "227.41", 200)
        .print("AAPL", TestTimes.START, "227.40", 100);
    tracker = new SubscriptionTracker(md, time);
    messaging = new CapturingMessaging();
    fanout = new QuoteFanout(md, tracker, new QuoteMessages(time, RealtimeTestSupport.calendarIn(SessionType.REGULAR)),
        time, RealtimeTestSupport.provider(messaging));
    tracker.connect("s1", 1, "ply_1", false);
    t0 = 1_000_000_000_000L;
  }

  void subscribe(String id, String destination) {
    tracker.subscribe("s1", id, StompDestinations.parse(destination).orElseThrow());
  }

  @Test
  void nothingIsSentForSymbolsNobodyWatches() {
    fanout.onMarketUpdate("AAPL");
    assertThat(fanout.flush(t0)).isZero();
    assertThat(fanout.dirtySymbols()).isEmpty();
    assertThat(md.quoteReads()).isZero();
  }

  @Test
  void quotesAreCoalescedToFourPerSecondAndCarryTheLatestState() {
    subscribe("q", "/topic/quote.AAPL");
    fanout.onMarketUpdate("AAPL");
    assertThat(fanout.flush(t0)).isEqualTo(1);
    QuoteDto first = (QuoteDto) messaging.to("/topic/quote.AAPL").get(0).payload();
    assertThat(first.symbol()).isEqualTo("AAPL");
    assertThat(first.bid()).isEqualByComparingTo("227.39");
    assertThat(first.session()).isEqualTo(SessionType.REGULAR);
    assertThat(first.ageMs()).isZero();

    // a burst inside the window: nothing until 250 ms have passed, then exactly one message with the newest quote
    md.quote("AAPL", TestTimes.START, "227.50", 100, "227.52", 100);
    for (int i = 0; i < 20; i++) {
      fanout.onMarketUpdate("AAPL");
    }
    assertThat(fanout.flush(t0 + 100 * MS)).isZero();
    assertThat(fanout.flush(t0 + 200 * MS)).isZero();
    assertThat(fanout.flush(t0 + 250 * MS)).isEqualTo(1);
    assertThat(fanout.flush(t0 + 600 * MS)).as("nothing new").isZero();
    assertThat(messaging.to("/topic/quote.AAPL")).hasSize(2);
    QuoteDto second = (QuoteDto) messaging.to("/topic/quote.AAPL").get(1).payload();
    assertThat(second.bid()).isEqualByComparingTo("227.50");
  }

  @Test
  void ticksGoOutAtMostOncePerSecond() {
    subscribe("t", "/topic/tick.AAPL");
    fanout.onMarketUpdate("AAPL");
    fanout.flush(t0);
    fanout.onMarketUpdate("AAPL");
    fanout.flush(t0 + 500 * MS);
    fanout.onMarketUpdate("AAPL");
    fanout.flush(t0 + 900 * MS);
    assertThat(messaging.to("/topic/tick.AAPL")).hasSize(1);
    fanout.flush(t0 + 1000 * MS);
    assertThat(messaging.to("/topic/tick.AAPL")).hasSize(2);
    TickDto tick = (TickDto) messaging.to("/topic/tick.AAPL").get(1).payload();
    assertThat(tick.last()).isEqualByComparingTo("227.40");
    assertThat(tick.change()).isEqualByComparingTo("2.40");
    assertThat(tick.quality()).isEqualTo(DataQuality.REALTIME_CONSOLIDATED);
    assertThat(messaging.to("/topic/quote.AAPL")).as("no quote subscribers").isEmpty();
  }

  @Test
  void barsSendTheFormingMinuteBar() {
    Bar older = new Bar("AAPL", Timeframe.MIN1, TestTimes.START.minusSeconds(60), new BigDecimal("227.00"),
        new BigDecimal("227.20"), new BigDecimal("226.90"), new BigDecimal("227.10"), 1000, null, 10, true);
    Bar forming = new Bar("AAPL", Timeframe.MIN1, TestTimes.START, new BigDecimal("227.10"),
        new BigDecimal("227.45"), new BigDecimal("227.05"), new BigDecimal("227.40"), 300, new BigDecimal("227.3"),
        3, true);
    md.withBars("AAPL", older, forming);
    subscribe("b", "/topic/bars.AAPL.1Min");
    fanout.prime("AAPL");
    assertThat(fanout.flush(t0)).isEqualTo(1);
    BarDto bar = (BarDto) messaging.to("/topic/bars.AAPL.1Min").get(0).payload();
    assertThat(bar.t()).isEqualTo(TestTimes.START);
    assertThat(bar.c()).isEqualByComparingTo("227.40");
    assertThat(bar.v()).isEqualTo(300);
  }

  @Test
  void aSubscriberWhoLeavesStopsTheSymbol() {
    subscribe("q", "/topic/quote.AAPL");
    fanout.onMarketUpdate("AAPL");
    fanout.flush(t0);
    fanout.onMarketUpdate("AAPL");
    tracker.unsubscribe("s1", "q");
    assertThat(fanout.flush(t0 + Duration.ofSeconds(1).toNanos())).isZero();
    assertThat(fanout.dirtySymbols()).isEmpty();
  }

  @Test
  void theThreadStartsAndStops() {
    fanout.start();
    assertThat(fanout.isRunning()).isTrue();
    fanout.stop();
    assertThat(fanout.isRunning()).isFalse();
  }
}
