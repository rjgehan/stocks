package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.trading.engine.TriggerEvaluator.Basis;
import com.stocks.trading.engine.TriggerEvaluator.Outcome;
import com.stocks.trading.engine.TriggerEvaluator.Params;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure trigger decision of §12.7: stop, opening gap, quote fallback and trailing watermarks. */
class TriggerEvaluatorTest {

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  static List<BigDecimal> prints(String... prices) {
    return Arrays.stream(prices).map(BigDecimal::new).toList();
  }

  static ExecQuote quote(String bid, String ask) {
    Instant t = Instant.parse("2026-10-09T14:00:00Z");
    return new ExecQuote("X", d(bid), 100, d(ask), 100, null, t, t, DataQuality.REALTIME_CONSOLIDATED, "test", false);
  }

  static Params stop(Side side, String stop) {
    return new Params(side, OrderType.STOP, d(stop), null, null);
  }

  static Params trailAmount(Side side, String amount) {
    return new Params(side, OrderType.TRAILING_STOP, null, d(amount), null);
  }

  static Params trailPercent(Side side, String pct) {
    return new Params(side, OrderType.TRAILING_STOP, null, null, d(pct));
  }

  // ------------------------------------------------------------------------------------------- stops

  @Test
  void aSellStopTriggersOnTheFirstPrintAtOrBelowItsStop() {
    Outcome o = TriggerEvaluator.decide(stop(Side.SELL, "95.00"), null, null, prints("96.00", "95.00", "94.00"),
        null, false, false);
    assertThat(o.triggered()).isTrue();
    assertThat(o.price()).isEqualByComparingTo("95.00");
    assertThat(o.basis()).isEqualTo(Basis.PRINT);
    assertThat(TriggerEvaluator.decide(stop(Side.SELL, "95.00"), null, null, prints("95.01"), null, false, false)
        .triggered()).isFalse();
  }

  @Test
  void aBuyStopTriggersOnAPrintAtOrAboveItsStop() {
    assertThat(TriggerEvaluator.decide(stop(Side.BUY, "105.00"), null, null, prints("104.99"), null, false, false)
        .triggered()).isFalse();
    Outcome o = TriggerEvaluator.decide(stop(Side.BUY, "105.00"), null, null, prints("104.99", "105.00"), null,
        false, false);
    assertThat(o.triggered()).isTrue();
    assertThat(o.price()).isEqualByComparingTo("105.00");
  }

  @Test
  void theQuoteDecidesOnlyInQuoteModeOrAtTheOpening() {
    Params sell = stop(Side.SELL, "95.00");
    ExecQuote through = quote("94.99", "95.01");
    assertThat(TriggerEvaluator.decide(sell, null, null, List.of(), through, false, false).triggered())
        .as("prints are flowing: the quote never triggers").isFalse();
    Outcome fallback = TriggerEvaluator.decide(sell, null, null, List.of(), through, true, false);
    assertThat(fallback.triggered()).isTrue();
    assertThat(fallback.basis()).isEqualTo(Basis.QUOTE);
    assertThat(fallback.price()).as("the bid for a sell").isEqualByComparingTo("94.99");
    Outcome gap = TriggerEvaluator.decide(sell, null, null, List.of(), through, false, true);
    assertThat(gap.basis()).isEqualTo(Basis.OPENING_QUOTE);
    assertThat(TriggerEvaluator.decide(sell, null, null, List.of(), quote("95.01", "95.03"), true, true)
        .triggered()).isFalse();
    Outcome buy = TriggerEvaluator.decide(stop(Side.BUY, "105.00"), null, null, List.of(), quote("104.98", "105.00"),
        true, false);
    assertThat(buy.triggered()).isTrue();
    assertThat(buy.price()).as("the ask for a buy").isEqualByComparingTo("105.00");
  }

  @Test
  void aPrintWinsOverTheQuoteAndAMissingOrOneSidedQuoteNeverTriggers() {
    Outcome o = TriggerEvaluator.decide(stop(Side.SELL, "95.00"), null, null, prints("94.50"), quote("90.00", "90.10"),
        true, true);
    assertThat(o.basis()).isEqualTo(Basis.PRINT);
    assertThat(o.price()).isEqualByComparingTo("94.50");
    assertThat(TriggerEvaluator.decide(stop(Side.SELL, "95.00"), null, null, List.of(), null, true, true).triggered())
        .isFalse();
    assertThat(TriggerEvaluator.decide(stop(Side.SELL, "95.00"), null, null, List.of(), quote("0", "95.10"), true,
        true).triggered()).as("no bid").isFalse();
  }

  // ------------------------------------------------------------------------------------------- trailing

  @Test
  void aSellTrailStartsAtTheFirstPrintAndRatchetsUpOnly() {
    Params p = trailPercent(Side.SELL, "5");
    Outcome start = TriggerEvaluator.decide(p, null, null, prints("100.00"), null, false, true);
    assertThat(start.triggered()).isFalse();
    assertThat(start.watermark()).isEqualByComparingTo("100.00");
    assertThat(start.stop()).isEqualByComparingTo("95.00");
    Outcome up = TriggerEvaluator.decide(p, start.watermark(), start.stop(), prints("104.00", "101.00"), null, false,
        false);
    assertThat(up.watermark()).isEqualByComparingTo("104.00");
    assertThat(up.stop()).isEqualByComparingTo("98.80");
    Outcome hit = TriggerEvaluator.decide(p, up.watermark(), up.stop(), prints("99.00", "98.80"), null, false, false);
    assertThat(hit.triggered()).isTrue();
    assertThat(hit.price()).isEqualByComparingTo("98.80");
    assertThat(hit.stop()).isEqualByComparingTo("98.80");
  }

  @Test
  void printsAreTakenInOrderSoADipBeforeANewHighNeverTriggersAgainstIt() {
    Params p = trailAmount(Side.SELL, "2.00");
    // watermark 100 → stop 98; 98.50 does not reach 98; then 110 moves the stop to 108, but 98.50 came earlier
    Outcome o = TriggerEvaluator.decide(p, d("100.00"), d("98.00"), prints("98.50", "110.00"), null, false, false);
    assertThat(o.triggered()).isFalse();
    assertThat(o.watermark()).isEqualByComparingTo("110.00");
    assertThat(o.stop()).isEqualByComparingTo("108.00");
    // the reverse order triggers: the high first, then the dip
    Outcome r = TriggerEvaluator.decide(p, d("100.00"), d("98.00"), prints("110.00", "98.50"), null, false, false);
    assertThat(r.triggered()).isTrue();
    assertThat(r.price()).isEqualByComparingTo("98.50");
  }

  @Test
  void aBuyTrailFollowsTheLowWatermarkAndRoundsItsStopUp() {
    Params p = trailPercent(Side.BUY, "3");
    Outcome start = TriggerEvaluator.decide(p, null, null, prints("33.33"), null, false, true);
    // 33.33 × 1.03 = 34.3299 → TICK_UP 34.33
    assertThat(start.stop()).isEqualByComparingTo("34.33");
    Outcome down = TriggerEvaluator.decide(p, start.watermark(), start.stop(), prints("30.00"), null, false, false);
    assertThat(down.watermark()).isEqualByComparingTo("30.00");
    assertThat(down.stop()).isEqualByComparingTo("30.90");
    Outcome hit = TriggerEvaluator.decide(p, down.watermark(), down.stop(), prints("30.90"), null, false, false);
    assertThat(hit.triggered()).isTrue();
  }

  @Test
  void aSellTrailRoundsItsStopDownToTheSubDollarTick() {
    // 0.8537 × 0.9 = 0.76833 → TICK_DOWN 0.7683
    assertThat(TriggerEvaluator.trailingStop(trailPercent(Side.SELL, "10"), d("0.8537")))
        .isEqualByComparingTo("0.7683");
    assertThat(TriggerEvaluator.trailingStop(trailAmount(Side.SELL, "1.00"), d("0.90"))).as("never ≤ 0").isNull();
  }

  @Test
  void withoutPrintsATrailStartsAtTheMidAndInQuoteModeFollowsTheTouch() {
    Params p = trailAmount(Side.SELL, "1.00");
    Outcome start = TriggerEvaluator.decide(p, null, null, List.of(), quote("99.98", "100.02"), false, true);
    assertThat(start.watermark()).isEqualByComparingTo("100.00");
    assertThat(start.stop()).isEqualByComparingTo("99.00");
    Outcome follow = TriggerEvaluator.decide(p, start.watermark(), start.stop(), List.of(), quote("101.50", "101.54"),
        true, false);
    assertThat(follow.watermark()).isEqualByComparingTo("101.50");
    assertThat(follow.stop()).isEqualByComparingTo("100.50");
    Outcome hit = TriggerEvaluator.decide(p, follow.watermark(), follow.stop(), List.of(), quote("100.50", "100.54"),
        true, false);
    assertThat(hit.triggered()).isTrue();
    assertThat(hit.basis()).isEqualTo(Basis.QUOTE);
    Outcome none = TriggerEvaluator.decide(p, null, null, List.of(), null, true, true);
    assertThat(none.watermark()).as("no starting price yet").isNull();
    assertThat(none.triggered()).isFalse();
  }

  @Test
  void outsideQuoteModeTheQuoteDoesNotMoveTheWatermark() {
    Params p = trailAmount(Side.SELL, "1.00");
    Outcome o = TriggerEvaluator.decide(p, d("100.00"), d("99.00"), List.of(), quote("120.00", "120.02"), false, true);
    assertThat(o.watermark()).isEqualByComparingTo("100.00");
    assertThat(o.triggered()).isFalse();
  }
}
