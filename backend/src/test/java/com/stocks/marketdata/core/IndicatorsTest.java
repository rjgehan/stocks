package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.marketdata.BarSeries;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.dto.IndicatorsDto;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Bar;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Exact daily indicators: the arithmetic, and which close counts as today's. */
class IndicatorsTest {

  static List<BigDecimal> closes(double... v) {
    List<BigDecimal> out = new ArrayList<>();
    for (double x : v) {
      out.add(BigDecimal.valueOf(x));
    }
    return out;
  }

  @Test
  void averagesReturnsAndRange() {
    List<BigDecimal> c = closes(10, 11, 12, 13, 14, 15);
    assertThat(Indicators.sma(c, 5)).isEqualByComparingTo("13");
    assertThat(Indicators.sma(c, 7)).isNull();
    assertThat(Indicators.ret(c, 5)).isEqualByComparingTo("0.5");
    assertThat(Indicators.ret(c, 6)).isNull();
    assertThat(Indicators.ratioMinusOne(new BigDecimal("102"), new BigDecimal("100"))).isEqualByComparingTo("0.02");
    assertThat(Indicators.extreme(c, 3, true)).isEqualByComparingTo("15");
    assertThat(Indicators.extreme(c, 3, false)).isEqualByComparingTo("13");
  }

  @Test
  void wilderRsi() {
    // up 2, down 1, up 1: avg gain = 2 -> 2x0.5+0 = 1 -> 1x0.5+1x0.5 = 1; avg loss = 0 -> 0x0.5+1x0.5 = 0.5 -> 0.25
    assertThat(Indicators.rsi(closes(10, 12, 11, 12), 2)).isEqualByComparingTo("80.00");   // 100 - 100/(1+4)
    assertThat(Indicators.rsi(closes(10, 11, 12), 2)).isEqualByComparingTo("100.00");      // no loss
    assertThat(Indicators.rsi(closes(10, 11), 2)).isNull();                                 // too short
    List<BigDecimal> falling = closes(20, 19, 18, 17, 16, 15);
    assertThat(Indicators.rsi(falling, 2)).isEqualByComparingTo("0.00");
  }

  // ------------------------------------------------------------------------------------------- the service

  static final Instant NOW = Instant.parse("2026-10-28T19:50:00Z");   // a Wednesday, 15:50 ET

  static Bar bar(Instant start, double close) {
    BigDecimal c = BigDecimal.valueOf(close);
    return new Bar("QQQ", Timeframe.DAY1, start, c, c, c, c, 1000L, null, 0, false);
  }

  static IndicatorsDto run(List<Bar> bars, BigDecimal price, Instant markAt, Instant now) {
    MarketDataService md = mock(MarketDataService.class);
    when(md.asset("QQQ")).thenReturn(Optional.of(mock(Asset.class)));
    when(md.bars(any())).thenReturn(new BarSeries("QQQ", Timeframe.DAY1, AdjustmentMode.ALL, bars, null, "test"));
    when(md.mark("QQQ")).thenReturn(new Mark(price, DataQuality.REALTIME_CONSOLIDATED, markAt, null));
    return new IndicatorService(md, new MutableTimeSource(now)).indicators("QQQ");
  }

  static List<Bar> daily(int n, Instant lastStart) {
    return IntStream.range(0, n).mapToObj(i -> bar(lastStart.minus(Duration.ofDays(n - 1 - i)), 100 + i)).toList();
  }

  @Test
  void todaysPartialBarTakesTheLivePrice() {
    // the last bar started today (04:00 UTC): the live price replaces its close
    IndicatorsDto d = run(daily(210, Instant.parse("2026-10-28T04:00:00Z")), new BigDecimal("400"), NOW, NOW);
    assertThat(d.todayIncluded()).isTrue();
    assertThat(d.closes()).isEqualTo(210);
    assertThat(d.price()).isEqualByComparingTo("400");
    // SMA5 of 305, 306, 307, 308 and today's 400
    assertThat(d.sma5()).isEqualByComparingTo("325.2");
    assertThat(d.sma200()).isNotNull();
    assertThat(d.vsSma200()).isPositive();
  }

  @Test
  void aNewSessionAppendsTheLivePriceButAWeekendDoesNot() {
    Instant friday = Instant.parse("2026-10-23T04:00:00Z");
    // Monday 15:50 ET, the bars end on Friday, the price traded Monday: appended as today's close
    Instant monday = Instant.parse("2026-10-26T19:50:00Z");
    IndicatorsDto mon = run(daily(60, friday), new BigDecimal("200"), monday.minusSeconds(5), monday);
    assertThat(mon.todayIncluded()).isTrue();
    assertThat(mon.closes()).isEqualTo(61);
    // Saturday: the last trade was Friday's, so Friday's close is not counted twice
    Instant saturday = Instant.parse("2026-10-24T19:50:00Z");
    IndicatorsDto sat = run(daily(60, friday), new BigDecimal("159"), Instant.parse("2026-10-23T19:59:59Z"), saturday);
    assertThat(sat.todayIncluded()).isFalse();
    assertThat(sat.closes()).isEqualTo(60);
    assertThat(sat.price()).isEqualByComparingTo("159");
    assertThat(sat.sma200()).isNull();   // 60 closes are not enough
  }
}
