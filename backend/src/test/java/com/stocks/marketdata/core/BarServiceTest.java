package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocks.calendar.InMemoryCalendars;
import com.stocks.common.domain.ChartRange;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.error.ApiException;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.BarRequest;
import com.stocks.contract.marketdata.BarSeries;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.marketdata.spi.Capabilities;
import com.stocks.marketdata.spi.MarketDataProvider;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** §15.5 BarService: range defaults, SIP history + IEX tail stitching, the forming bar, filters, caching. */
class BarServiceTest {

  static final Instant NOW = Instant.parse("2026-10-09T15:00:00Z");               // Fri 11:00 ET
  final MutableTimeSource time = new MutableTimeSource(NOW);
  final MarketCalendar calendar = InMemoryCalendars.bundled();
  final List<BarQuery> queries = new CopyOnWriteArrayList<>();

  /** One 1Min bar per minute in [from, to]; SIP closes 100.xx, IEX closes 200.xx (partial volume). */
  final MarketDataProvider provider = new MarketDataProvider() {
    @Override
    public String id() {
      return "alpaca";
    }

    @Override
    public Capabilities capabilities() {
      return null;
    }

    @Override
    public Map<String, List<Bar>> bars(BarQuery q, RefreshPriority priority) {
      queries.add(q);
      boolean sip = "sip".equals(q.feed());
      List<Bar> out = new ArrayList<>();
      Duration step = BarMath.length(q.tf()) == null ? Duration.ofDays(1) : BarMath.length(q.tf());
      int i = 0;
      for (Instant t = q.from(); !t.isAfter(q.to()); t = t.plus(step), i++) {
        BigDecimal c = new BigDecimal(sip ? "100" : "200").add(BigDecimal.valueOf(i, 2));
        out.add(new Bar("AAPL", q.tf(), t, c, c, c, c, 100, c, 1, !sip));
      }
      return Map.of("AAPL", out);
    }
  };

  BarService service(MarketFeed feed) {
    return new BarService(provider, true, feed, calendar, time);
  }

  @Test
  void oneDayStitchesSipHistoryWithAnIexTailAndTheFormingBar() {
    MarketFeed feed = mock(MarketFeed.class);
    Bar forming = new Bar("AAPL", Timeframe.MIN1, NOW, new BigDecimal("300"), new BigDecimal("300"),
        new BigDecimal("300"), new BigDecimal("300"), 5, null, 1, true);
    when(feed.currentMinuteBar("AAPL")).thenReturn(Optional.of(forming));
    BarSeries s = service(feed).bars(new BarRequest("AAPL", ChartRange.D1, null, null, null, null, AdjustmentMode.SPLIT,
        true));
    assertThat(s.timeframe()).isEqualTo(Timeframe.MIN1);
    assertThat(s.bars().getFirst().start()).isEqualTo(Instant.parse("2026-10-09T08:00:00Z"));   // 04:00 ET pre-open
    BarQuery sip = queries.stream().filter(q -> "sip".equals(q.feed())).findFirst().orElseThrow();
    BarQuery iex = queries.stream().filter(q -> "iex".equals(q.feed())).findFirst().orElseThrow();
    assertThat(sip.to()).isEqualTo(NOW.minus(Duration.ofMinutes(16)));
    assertThat(sip.adj()).isEqualTo(AdjustmentMode.SPLIT);
    assertThat(iex.from()).isEqualTo(Instant.parse("2026-10-09T14:44:00Z"));
    // SIP wins on equal starts; the tail starts right after the last SIP bar
    Bar at1444 = s.bars().stream().filter(b -> b.start().equals(Instant.parse("2026-10-09T14:44:00Z"))).findFirst()
        .orElseThrow();
    assertThat(at1444.partialVolume()).isFalse();
    assertThat(s.tailFrom()).isEqualTo(Instant.parse("2026-10-09T14:45:00Z"));
    assertThat(s.bars().getLast()).isEqualTo(forming);
    assertThat(s.source()).isEqualTo("alpaca");
  }

  @Test
  void regularHoursOnlyAndExplicitWindowsAndCache() {
    MarketFeed feed = mock(MarketFeed.class);
    when(feed.currentMinuteBar("AAPL")).thenReturn(Optional.empty());
    BarService service = service(feed);
    BarSeries rth = service.bars(new BarRequest("AAPL", ChartRange.D1, null, null, null, null, AdjustmentMode.SPLIT,
        false));
    assertThat(rth.bars().getFirst().start()).isEqualTo(Instant.parse("2026-10-09T13:30:00Z"));
    int calls = queries.size();
    service.bars(new BarRequest("AAPL", ChartRange.D1, null, null, null, null, AdjustmentMode.SPLIT, false));
    assertThat(queries).hasSize(calls);                                          // cached
    BarSeries window = service.bars(new BarRequest("AAPL", null, Timeframe.MIN5,
        Instant.parse("2026-10-08T14:00:00Z"), Instant.parse("2026-10-08T15:00:00Z"), 5, AdjustmentMode.RAW, true));
    assertThat(window.bars()).hasSize(5);                                        // the most recent 5
    assertThat(window.tailFrom()).isNull();                                      // fully past: SIP only
    assertThatThrownBy(() -> service.bars(new BarRequest("AAPL", null, Timeframe.MIN1, NOW.plusSeconds(60), null,
        null, AdjustmentMode.SPLIT, true))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.bars(new BarRequest("AAPL", null, Timeframe.MIN1, null, null, 1001,
        AdjustmentMode.SPLIT, true))).isInstanceOf(ApiException.class);
  }

  @Test
  void rangeDefaults() {
    assertThat(BarService.defaultTimeframe(ChartRange.D5)).isEqualTo(Timeframe.MIN5);
    assertThat(BarService.defaultTimeframe(ChartRange.M1)).isEqualTo(Timeframe.MIN30);
    assertThat(BarService.defaultTimeframe(ChartRange.YTD)).isEqualTo(Timeframe.DAY1);
    assertThat(BarService.defaultTimeframe(ChartRange.Y5)).isEqualTo(Timeframe.WEEK1);
    assertThat(BarService.defaultTimeframe(ChartRange.ALL)).isEqualTo(Timeframe.MONTH1);
    BarService s = service(mock(MarketFeed.class));
    assertThat(s.rangeStart(ChartRange.D5, NOW, true)).isEqualTo(Instant.parse("2026-10-05T08:00:00Z"));
    assertThat(s.rangeStart(ChartRange.YTD, NOW, true)).isEqualTo(Instant.parse("2026-01-01T05:00:00Z"));
    assertThat(s.rangeStart(ChartRange.ALL, NOW, true)).isEqualTo(Instant.parse("2016-01-01T05:00:00Z"));
    // on Saturday, 1D is Friday's session
    assertThat(s.rangeStart(ChartRange.D1, Instant.parse("2026-10-10T15:00:00Z"), true))
        .isEqualTo(Instant.parse("2026-10-09T08:00:00Z"));
  }

  @Test
  void aggregation() {
    List<Bar> minutes = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      BigDecimal p = BigDecimal.valueOf(100 + i);
      minutes.add(new Bar("X", Timeframe.MIN1, Instant.parse("2026-10-09T13:30:00Z").plusSeconds(60L * i), p, p.add(
          BigDecimal.ONE), p.subtract(BigDecimal.ONE), p, 10, p, 1, false));
    }
    List<Bar> five = BarMath.aggregate(minutes, Timeframe.MIN5);
    assertThat(five).hasSize(2);
    assertThat(five.getFirst().open()).isEqualByComparingTo("100");
    assertThat(five.getFirst().close()).isEqualByComparingTo("104");
    assertThat(five.getFirst().high()).isEqualByComparingTo("105");
    assertThat(five.getFirst().low()).isEqualByComparingTo("99");
    assertThat(five.getFirst().volume()).isEqualTo(50);
    assertThat(five.getFirst().vwap()).isEqualByComparingTo("102");
  }
}
