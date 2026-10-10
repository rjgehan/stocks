package com.stocks.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.marketdata.MarkBasis;
import com.stocks.contract.marketdata.Print;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.RefreshPriority;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The test kit's market-data double plays its script exactly against the injected clock. */
class ScriptedMarketDataTest {

  static final Instant T0 = TestTimes.START;

  @Test
  void quotesPrintsAndMarksFollowTheClock() {
    MutableTimeSource time = TestTimes.clock();
    ScriptedMarketData md = new ScriptedMarketData(time)
        .withAsset("AAPL")
        .prevClose("AAPL", "225.00")
        .quote("AAPL", T0, "227.40", 300, "227.42", 200)
        .quote("AAPL", T0.plusSeconds(2), "227.50", 100, "227.52", 100)
        .print("AAPL", T0.plusMillis(400), "227.41", 100)
        .print("AAPL", T0.plusSeconds(3), "227.51", 50);

    QuoteView q = md.quote("aapl");
    assertThat(q.bid()).isEqualByComparingTo("227.40");
    assertThat(q.last()).isNull();
    assertThat(q.prevClose()).isEqualByComparingTo("225.00");
    assertThat(md.mark("AAPL").basis()).isEqualTo(MarkBasis.IEX_MID);
    assertThat(md.mark("AAPL").price()).isEqualByComparingTo("227.4100");

    time.advance(Duration.ofSeconds(1));
    assertThat(md.quote("AAPL").last()).isEqualByComparingTo("227.41");
    assertThat(md.quote("AAPL").change()).isEqualByComparingTo("2.41");
    assertThat(md.mark("AAPL").basis()).isEqualTo(MarkBasis.CONSOLIDATED_LAST);
    assertThat(md.drainPrints("AAPL")).extracting(Print::price).extracting(Object::toString).containsExactly("227.41");
    assertThat(md.drainPrints("AAPL")).as("drained once").isEmpty();

    time.advance(Duration.ofSeconds(3));
    assertThat(md.drainPrints("AAPL")).hasSize(1);
    assertThat(md.quote("AAPL").bid()).isEqualByComparingTo("227.50");
    assertThat(md.quote("MSFT").quality()).isEqualTo(DataQuality.NONE);
    assertThat(md.asset("AAPL")).isPresent();
    assertThat(md.search("AA", 5)).hasSize(1);
  }

  @Test
  void executableQuotesNeverLookAhead() {
    MutableTimeSource time = TestTimes.clock();
    ScriptedMarketData md = new ScriptedMarketData(time)
        .quote("NVDA", T0, "131.10", 100, "131.20", 100)
        .quote("NVDA", T0.plusSeconds(1), "131.15", 100, "131.25", 100);
    assertThat(md.executableQuote("NVDA", T0.plusMillis(600), SessionType.REGULAR)).isEmpty();
    time.advance(Duration.ofSeconds(1));
    assertThat(md.executableQuote("NVDA", T0.plusMillis(600), SessionType.REGULAR)).get()
        .satisfies(q -> assertThat(q.ask()).isEqualByComparingTo("131.25"));
    md.halt("NVDA", T0.plusSeconds(1), null);
    assertThat(md.isHalted("NVDA")).isTrue();
    assertThat(md.executableQuote("NVDA", T0, SessionType.REGULAR)).isEmpty();
  }

  @Test
  void refreshRequestsNotifyListenersAndLeasesAreTracked() {
    MutableTimeSource time = TestTimes.clock();
    List<String> updates = new ArrayList<>();
    ScriptedMarketData md = new ScriptedMarketData(time).addListener(updates::add);
    md.requestRefresh("spy", RefreshPriority.ENGINE);
    assertThat(updates).containsExactly("SPY");
    assertThat(md.refreshRequests()).singleElement().satisfies(r -> assertThat(r.symbol()).isEqualTo("SPY"));
    var handle = md.lease("SPY", com.stocks.contract.marketdata.LeasePriority.VIEWING, "ws:1");
    assertThat(md.leases().get("SPY")).containsExactly("ws:1");
    handle.close();
    assertThat(md.leases().get("SPY")).isEmpty();
    assertThat(md.mark("SPY").quality()).isEqualTo(DataQuality.NONE);
  }
}
