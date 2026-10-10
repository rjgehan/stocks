package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderEventDto;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.PreviewDto;
import com.stocks.contract.trading.dto.ReplaceOrderRequest;
import com.stocks.testkit.TestTimes;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden engine scenarios for STOP, STOP_LIMIT and TRAILING_STOP (§11.1, §12.7) on the scripted market with the
 * engine on the calling thread: triggers on consolidated prints and on the bid/ask fallback, the opening gap after a
 * queue or an overnight pause, a stop-limit that triggers and rests, percent and amount trailing stops with watermark
 * moves, expiry, cancel, halts, replace, and a restart that never triggers on what happened while the engine was
 * down. Every trigger is followed by a fresh latency window before the fill, through the ordinary {@link FillModel}.
 * Invariants I1–I9 are asserted after each test by the base class.
 */
class StopOrderScenarioTest extends TradingTestBase {

  // ------------------------------------------------------------------------------------------- STOP

  @Test
  void aSellStopTriggersOnAConsolidatedPrintThenFillsAsAMarketOrder() {
    String s = symbol();
    Player p = holder(s, 10);
    time.advance(Duration.ofSeconds(1));
    PlaceOrderResponse r = place(p, stop(cid(), s, Side.SELL, "10", "95.00", TimeInForce.DAY));
    OrderDto o = r.order();
    assertThat(o.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(o.type()).isEqualTo(OrderType.STOP);
    assertThat(o.stopPrice()).isEqualByComparingTo("95.00");
    assertThat(o.reservedQty()).isEqualByComparingTo("10");
    assertThat(o.reservedCash()).isEqualByComparingTo("0");
    assertThat(o.triggered()).isFalse();
    assertThat(r.estimate().price()).as("a stop is expected to fill around its trigger").isEqualByComparingTo("95.00");
    assertThat(md.leases().get(s)).contains("order:" + o.orderId());

    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 1000, "100.00", 1000);
    print(s, "99.99", 100);
    step();
    OrderDto armed = order(p, o.orderId());
    assertThat(armed.status()).isEqualTo(OrderStatus.WORKING);
    assertThat(armed.waitReason()).isEqualTo(WaitReason.NOT_TRIGGERED);

    time.advance(Duration.ofSeconds(1));
    print(s, "95.01", 100);                                  // above the stop
    step();
    assertThat(order(p, o.orderId()).triggered()).isFalse();

    time.advance(Duration.ofSeconds(1));
    print(s, "95.00", 200);                                  // at the stop: a sell stop triggers at or below it
    step();
    Instant triggeredAt = time.now();
    OrderDto t = order(p, o.orderId());
    assertThat(t.triggered()).isTrue();
    assertThat(t.triggeredAt()).isEqualTo(triggeredAt);
    assertThat(t.status()).isEqualTo(OrderStatus.WORKING);
    assertThat(t.filledQty()).as("the trigger step never fills").isEqualByComparingTo("0");
    assertThat(t.waitReason()).isEqualTo(WaitReason.LATENCY);
    assertThat(t.eligibleAt()).isAfter(triggeredAt.plusMillis(349)).isBefore(triggeredAt.plusMillis(751));
    assertThat(trigger(t)).containsEntry("basis", "PRINT");
    assertThat(dec(trigger(t).get("price"))).isEqualByComparingTo("95.00");
    assertThat(dec(trigger(t).get("stopPrice"))).isEqualByComparingTo("95.00");

    time.advance(Duration.ofSeconds(1));
    quote(s, "94.90", 300, "94.95", 300);
    step();
    OrderDto f = order(p, o.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.collarPrice()).as("collar set at the first post-trigger evaluation").isEqualByComparingTo("90.15");
    assertThat(f.reservedQty()).isEqualByComparingTo("0");
    assertThat(f.executions()).singleElement().satisfies(x -> {
      assertThat(x.price()).isEqualByComparingTo("94.90");
      assertThat(x.qty()).isEqualByComparingTo("10");
      assertThat(x.liquidity()).isEqualTo(Liquidity.TOUCH);
      assertThat(x.executedAt()).isAfter(t.eligibleAt());
    });
    assertThat(f.events()).extracting(OrderEventDto::type).containsSubsequence(OrderEventType.ACCEPTED,
        OrderEventType.WORKING, OrderEventType.TRIGGERED, OrderEventType.FILL);
    assertThat(accountQueries.positions(p.accountId())).isEmpty();
    assertThat(md.leases().getOrDefault(s, java.util.Set.of())).doesNotContain("order:" + o.orderId());
  }

  @Test
  void aBuyStopTriggersOnTheAskWhenTheSymbolHasNoConsolidatedPrints() {
    Player p = human();
    String s = symbol();
    md.prevClose(s, "100.00");
    quote(s, "99.98", 500, "100.00", 500);
    PlaceOrderResponse r = place(p, stop(cid(), s, Side.BUY, "2", "105.00", TimeInForce.DAY));
    // §10.3: qty × max(stop, ref) × 102% + estCAT
    assertThat(r.order().reservedCash()).isEqualByComparingTo("214.20");
    assertThat(r.buyingPowerAfter()).isEqualByComparingTo("285.80");

    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 500, "100.00", 500);
    step();
    assertThat(order(p, r.order().orderId()).waitReason()).isEqualTo(WaitReason.NOT_TRIGGERED);

    time.advance(Duration.ofSeconds(1));
    quote(s, "104.95", 500, "105.00", 500);                 // ask ≥ stop, no consolidated print ever
    step();
    OrderDto t = order(p, r.order().orderId());
    assertThat(t.triggered()).isTrue();
    assertThat(trigger(t)).containsEntry("basis", "QUOTE");
    assertThat(dec(trigger(t).get("price"))).isEqualByComparingTo("105.00");

    time.advance(Duration.ofSeconds(1));
    quote(s, "105.00", 500, "105.02", 500);
    step();
    OrderDto f = order(p, r.order().orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions()).singleElement().satisfies(x -> assertThat(x.price()).isEqualByComparingTo("105.02"));
    assertThat(f.reservedCash()).isEqualByComparingTo("0");
    assertThat(accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE).cashTotal())
        .isEqualByComparingTo("289.96");
  }

  @Test
  void whilePrintsAreRecentTheQuoteAloneNeverTriggersAndAfter15SecondsItDoes() {
    String s = symbol();
    Player p = holder(s, 5);
    OrderDto o = place(p, stop(cid(), s, Side.SELL, "5", "95.00", TimeInForce.DAY)).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 1000, "100.00", 1000);
    print(s, "99.99", 100);
    step();

    time.advance(Duration.ofSeconds(1));
    print(s, "99.50", 100);
    quote(s, "94.00", 100, "94.10", 100);                    // a bad quote, but prints decide while they flow
    step();
    assertThat(order(p, o.orderId()).triggered()).isFalse();

    time.advance(Duration.ofSeconds(16));                     // no consolidated print for > 15 s
    quote(s, "94.00", 100, "94.10", 100);
    step();
    OrderDto t = order(p, o.orderId());
    assertThat(t.triggered()).isTrue();
    assertThat(trigger(t)).containsEntry("basis", "QUOTE");
    assertThat(dec(trigger(t).get("price"))).isEqualByComparingTo("94.00");
  }

  @Test
  void aStopEnteredDuringTheSessionIsNoOpeningSoItsFirstQuoteDoesNotTriggerIt() {
    String s = symbol();
    Player p = holder(s, 3);
    // the last trade (the reference) is 100.00, but the bid is already below the stop
    OrderDto o = place(p, stop(cid(), s, Side.SELL, "3", "99.99", TimeInForce.DAY)).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.97", 1000, "99.99", 1000);
    print(s, "100.00", 100);
    step();
    OrderDto armed = order(p, o.orderId());
    assertThat(armed.status()).isEqualTo(OrderStatus.WORKING);
    assertThat(armed.triggered()).as("prints flow: only a print at or below 99.99 triggers").isFalse();
    time.advance(Duration.ofSeconds(1));
    print(s, "99.99", 100);
    step();
    assertThat(trigger(order(p, o.orderId()))).containsEntry("basis", "PRINT");
  }

  // ------------------------------------------------------------------------------------------- opening gap

  @Test
  void aStopQueuedForTheOpenTriggersOnTheGapAndFillsAtTheGappedPrice() {
    String s = symbol();
    Player p = holder(s, 10);
    time.set(et("2026-10-09T17:00:00"));
    PreviewDto preview = orders.preview(p.principal(), stop(null, s, Side.SELL, "10", "95.00", TimeInForce.DAY));
    assertThat(preview.valid()).isTrue();
    assertThat(preview.sessionNote()).isEqualTo("Order will queue for Mon 09:30 ET");
    OrderDto o = place(p, stop(cid(), s, Side.SELL, "10", "95.00", TimeInForce.DAY)).order();
    assertThat(o.waitReason()).isEqualTo(WaitReason.QUEUED_FOR_OPEN);
    assertThat(o.sessionDate()).isEqualTo(TestTimes.COLUMBUS_DAY);
    assertThat(o.expiresAt()).isEqualTo(et("2026-10-12T16:00:00"));

    // a print before the order is eligible keeps the symbol in print mode but is no trigger evidence
    time.set(et("2026-10-12T09:30:00").plusMillis(100));
    print(s, "96.00", 100);
    step();
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.PENDING);

    time.set(et("2026-10-12T09:30:01"));
    quote(s, "91.00", 500, "91.05", 500);                    // the first executable quote is through the stop
    step();
    OrderDto t = order(p, o.orderId());
    assertThat(t.status()).isEqualTo(OrderStatus.WORKING);
    assertThat(t.triggered()).isTrue();
    assertThat(trigger(t)).containsEntry("basis", "OPENING_QUOTE");
    assertThat(dec(trigger(t).get("price"))).isEqualByComparingTo("91.00");

    time.advance(Duration.ofSeconds(1));
    quote(s, "91.00", 500, "91.05", 500);
    step();
    OrderDto f = order(p, o.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions()).singleElement().satisfies(x -> {
      assertThat(x.price()).as("a gap fills at the market, not at the stop").isEqualByComparingTo("91.00");
      assertThat(x.tradeDate()).isEqualTo(TestTimes.COLUMBUS_DAY);
    });
  }

  @Test
  void aGtcStopPausesOvernightAndTriggersOnTheFirstPrintThroughItAtTheOpen() {
    String s = symbol();
    Player p = holder(s, 10);
    OrderDto o = place(p, stop(cid(), s, Side.SELL, "10", "95.00", TimeInForce.GTC)).order();
    assertThat(o.expiresAt()).isEqualTo(et("2026-12-08T16:00:00"));
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 1000, "100.00", 1000);
    print(s, "99.99", 100);
    step();
    assertThat(order(p, o.orderId()).waitReason()).isEqualTo(WaitReason.NOT_TRIGGERED);

    time.set(et("2026-10-09T16:00:01"));
    step();
    assertThat(order(p, o.orderId()).waitReason()).isEqualTo(WaitReason.SESSION_CLOSED);
    time.set(et("2026-10-09T18:00:00"));
    print(s, "80.00", 100);                                  // after hours: a stop never triggers outside REGULAR
    quote(s, "79.90", 100, "80.10", 100);
    step();
    assertThat(order(p, o.orderId()).triggered()).isFalse();

    time.set(et("2026-10-12T09:30:00"));
    step();
    assertThat(order(p, o.orderId()).events()).extracting(OrderEventDto::type).contains(OrderEventType.RESUMED);
    time.set(et("2026-10-12T09:30:01"));
    print(s, "90.00", 500);
    quote(s, "89.95", 500, "90.05", 500);
    step();
    OrderDto t = order(p, o.orderId());
    assertThat(t.triggered()).isTrue();
    assertThat(trigger(t)).containsEntry("basis", "PRINT");
    assertThat(dec(trigger(t).get("price"))).as("the open print, not yesterday's after-hours print")
        .isEqualByComparingTo("90.00");

    time.advance(Duration.ofSeconds(1));
    quote(s, "89.95", 500, "90.05", 500);
    step();
    OrderDto f = order(p, o.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions()).singleElement().satisfies(x -> {
      assertThat(x.price()).isEqualByComparingTo("89.95");
      assertThat(x.tradeDate()).isEqualTo(LocalDate.of(2026, 10, 12));
    });
  }

  // ------------------------------------------------------------------------------------------- STOP_LIMIT

  @Test
  void aStopLimitTriggersButDoesNotFillThroughItsLimitThenRestsAtIt() {
    String s = symbol();
    Player p = holder(s, 10);
    PlaceOrderResponse r = place(p, stopLimit(cid(), s, Side.SELL, "10", "95.00", "94.50", TimeInForce.DAY));
    OrderDto o = r.order();
    assertThat(o.limitPrice()).isEqualByComparingTo("94.50");
    assertThat(r.estimate().price()).as("max(stop, limit) for a sell").isEqualByComparingTo("95.00");
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 1000, "100.00", 1000);
    print(s, "99.99", 100);
    step();

    time.advance(Duration.ofSeconds(1));
    print(s, "94.00", 300);                                  // gaps through both the stop and the limit
    step();
    assertThat(order(p, o.orderId()).triggered()).isTrue();

    time.advance(Duration.ofSeconds(1));
    quote(s, "93.90", 500, "94.00", 500);
    step();
    OrderDto resting = order(p, o.orderId());
    assertThat(resting.status()).as("triggered, but the bid is below the limit").isEqualTo(OrderStatus.WORKING);
    assertThat(resting.filledQty()).isEqualByComparingTo("0");
    assertThat(resting.waitReason()).isEqualTo(WaitReason.LIMIT_NOT_REACHED);
    assertThat(resting.collarPrice()).as("a limit has no collar").isNull();
    assertThat(resting.reservedQty()).isEqualByComparingTo("10");

    time.advance(Duration.ofSeconds(2));
    quote(s, "94.55", 500, "94.60", 500);                    // recovers: the resting sell fills at its limit
    step();
    OrderDto f = order(p, o.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions()).singleElement().satisfies(x -> {
      assertThat(x.price()).isEqualByComparingTo("94.50");
      assertThat(x.liquidity()).isEqualTo(Liquidity.RESTING);
    });
  }

  @Test
  void aBuyStopLimitHoldsItsLimitAndFillsAtTheTouchWhenMarketableOnArrival() {
    Player p = human();
    String s = symbol();
    print(s, "50.00", 100);
    quote(s, "49.98", 500, "50.00", 500);
    PlaceOrderResponse r = place(p, stopLimit(cid(), s, Side.BUY, "4", "52.00", "52.50", TimeInForce.GTC));
    assertThat(r.order().reservedCash()).as("qty × limit + estCAT").isEqualByComparingTo("210.00");
    assertThat(r.estimate().price()).as("min(stop, limit) for a buy").isEqualByComparingTo("52.00");
    time.advance(Duration.ofSeconds(1));
    print(s, "50.00", 100);
    quote(s, "49.98", 500, "50.00", 500);
    step();
    time.advance(Duration.ofSeconds(1));
    print(s, "52.05", 100);
    step();
    assertThat(order(p, r.order().orderId()).triggered()).isTrue();
    time.advance(Duration.ofSeconds(1));
    quote(s, "52.01", 500, "52.03", 500);
    step();
    OrderDto f = order(p, r.order().orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions()).singleElement().satisfies(x -> {
      assertThat(x.price()).as("price improvement below the limit").isEqualByComparingTo("52.03");
      assertThat(x.liquidity()).isEqualTo(Liquidity.TOUCH);
    });
    assertThat(f.reservedCash()).isEqualByComparingTo("0");
    assertThat(accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE).cashTotal())
        .isEqualByComparingTo("291.88");
  }

  // ------------------------------------------------------------------------------------------- TRAILING_STOP

  @Test
  void aPercentTrailingSellStopFollowsTheHighWatermarkAndTriggersOffIt() {
    String s = symbol();
    Player p = holder(s, 10);
    PlaceOrderResponse r = place(p, trailing(cid(), s, Side.SELL, "10", null, "5"));
    OrderDto o = r.order();
    assertThat(o.trailPercent()).isEqualByComparingTo("5");
    assertThat(o.trailAmount()).isNull();
    assertThat(o.stopPrice()).as("computed once the order works").isNull();
    assertThat(o.timeInForce()).isEqualTo(TimeInForce.DAY);
    assertThat(r.estimate().price()).as("starting stop TICK_DOWN(100 × 0.95)").isEqualByComparingTo("95.00");

    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 1000, "100.00", 1000);
    print(s, "100.00", 100);
    step();
    OrderDto start = order(p, o.orderId());
    assertThat(start.waitReason()).isEqualTo(WaitReason.NOT_TRIGGERED);
    assertThat(start.watermarkPrice()).isEqualByComparingTo("100.00");
    assertThat(start.stopPrice()).isEqualByComparingTo("95.00");

    time.advance(Duration.ofSeconds(1));
    print(s, "104.00", 100);
    step();
    OrderDto up = order(p, o.orderId());
    assertThat(up.watermarkPrice()).isEqualByComparingTo("104.00");
    assertThat(up.stopPrice()).isEqualByComparingTo("98.80");

    time.advance(Duration.ofSeconds(1));
    print(s, "99.00", 100);                                  // above 98.80: no trigger …
    print(s, "110.00", 100);                                 // … and a later new high cannot reach back to it
    step();
    OrderDto high = order(p, o.orderId());
    assertThat(high.triggered()).isFalse();
    assertThat(high.watermarkPrice()).isEqualByComparingTo("110.00");
    assertThat(high.stopPrice()).isEqualByComparingTo("104.50");

    time.advance(Duration.ofSeconds(1));
    print(s, "105.00", 100);
    step();
    assertThat(order(p, o.orderId()).triggered()).isFalse();

    time.advance(Duration.ofSeconds(1));
    print(s, "104.50", 100);
    step();
    OrderDto t = order(p, o.orderId());
    assertThat(t.triggered()).isTrue();
    assertThat(dec(trigger(t).get("price"))).isEqualByComparingTo("104.50");
    assertThat(dec(trigger(t).get("watermark"))).isEqualByComparingTo("110.00");
    assertThat(t.events()).as("TRAIL_UPDATED is throttled to one per 10 s").filteredOn(
        e -> e.type() == OrderEventType.TRAIL_UPDATED).hasSize(1);

    time.advance(Duration.ofSeconds(1));
    quote(s, "104.40", 1000, "104.45", 1000);
    step();
    OrderDto f = order(p, o.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions()).singleElement().satisfies(x -> assertThat(x.price()).isEqualByComparingTo("104.40"));
    assertThat(f.stopPrice()).isEqualByComparingTo("104.50");
  }

  @Test
  void anAmountTrailingBuyStopFollowsTheLowWatermark() {
    Player p = human();
    String s = symbol();
    print(s, "50.00", 100);
    quote(s, "49.98", 500, "50.00", 500);
    PlaceOrderResponse r = place(p, trailing(cid(), s, Side.BUY, "4", "2.00", null));
    // §10.3: qty × (ref + trail) × 102% + estCAT
    assertThat(r.order().reservedCash()).isEqualByComparingTo("212.16");
    assertThat(r.estimate().price()).isEqualByComparingTo("52.00");
    String id = r.order().orderId();

    time.advance(Duration.ofSeconds(1));
    print(s, "50.00", 100);
    quote(s, "49.98", 500, "50.00", 500);
    step();
    assertThat(order(p, id).stopPrice()).isEqualByComparingTo("52.00");

    time.advance(Duration.ofSeconds(1));
    print(s, "48.00", 100);
    step();
    assertThat(order(p, id).watermarkPrice()).isEqualByComparingTo("48.00");
    assertThat(order(p, id).stopPrice()).isEqualByComparingTo("50.00");

    time.advance(Duration.ofSeconds(1));
    print(s, "49.50", 100);
    step();
    assertThat(order(p, id).triggered()).isFalse();

    time.advance(Duration.ofSeconds(11));
    print(s, "47.00", 100);
    step();
    OrderDto lower = order(p, id);
    assertThat(lower.stopPrice()).isEqualByComparingTo("49.00");
    assertThat(lower.events()).filteredOn(e -> e.type() == OrderEventType.TRAIL_UPDATED)
        .as("a second TRAIL_UPDATED once 10 s have passed").hasSize(2);

    time.advance(Duration.ofSeconds(1));
    print(s, "49.00", 100);
    step();
    assertThat(order(p, id).triggered()).isTrue();

    time.advance(Duration.ofSeconds(1));
    quote(s, "48.98", 500, "49.02", 500);
    step();
    OrderDto f = order(p, id);
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions()).singleElement().satisfies(x -> assertThat(x.price()).isEqualByComparingTo("49.02"));
    assertThat(f.reservedCash()).isEqualByComparingTo("0");
    assertThat(accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE).cashTotal())
        .isEqualByComparingTo("303.92");
  }

  @Test
  void aTrailingStopStartsAtTheMidWithoutPrintsAndRestartsFromItsPersistedWatermark() {
    String s = symbol();
    Player p = holder(s, 3);
    OrderDto o = place(p, trailing(cid(), s, Side.SELL, "3", "1.00", null)).order();
    time.advance(Duration.ofSeconds(20));                    // the holder's prints are now > 15 s old
    quote(s, "99.98", 500, "100.02", 500);
    step();
    OrderDto start = order(p, o.orderId());
    assertThat(start.watermarkPrice()).as("the executable mid").isEqualByComparingTo("100.00");
    assertThat(start.stopPrice()).isEqualByComparingTo("99.00");

    time.advance(Duration.ofSeconds(1));
    quote(s, "101.50", 500, "101.54", 500);                  // quote mode: the watermark follows the bid
    step();
    assertThat(order(p, o.orderId()).watermarkPrice()).isEqualByComparingTo("101.50");

    time.advance(Duration.ofSeconds(5));
    engine.restart();
    step();
    OrderDto reloaded = order(p, o.orderId());
    assertThat(reloaded.watermarkPrice()).isEqualByComparingTo("101.50");
    assertThat(reloaded.stopPrice()).isEqualByComparingTo("100.50");
    assertThat(reloaded.triggered()).isFalse();

    time.advance(Duration.ofSeconds(1));
    print(s, "100.50", 100);
    step();
    assertThat(order(p, o.orderId()).triggered()).isTrue();
  }

  // ------------------------------------------------------------------------------------------- lifecycle

  @Test
  void anUntriggeredDayStopExpiresAtTheCloseAndReleasesItsHold() {
    Player p = human();
    String s = symbol();
    md.prevClose(s, "100.00");
    quote(s, "99.98", 500, "100.00", 500);
    OrderDto o = place(p, stop(cid(), s, Side.BUY, "2", "105.00", TimeInForce.DAY)).order();
    assertThat(o.expiresAt()).isEqualTo(et("2026-10-09T16:00:00"));
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 500, "100.00", 500);
    step();
    time.set(et("2026-10-09T16:00:00"));
    step();
    OrderDto x = order(p, o.orderId());
    assertThat(x.status()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(x.triggered()).isFalse();
    assertThat(x.reservedCash()).isEqualByComparingTo("0");
    assertThat(accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE).buyingPower())
        .isEqualByComparingTo("500.00");
    assertThat(md.leases().getOrDefault(s, java.util.Set.of())).doesNotContain("order:" + o.orderId());
  }

  @Test
  void aTrailingStopIsDayOnlyAndExpiresAtTheClose() {
    String s = symbol();
    Player p = holder(s, 2);
    OrderDto o = place(p, trailing(cid(), s, Side.SELL, "2", null, "10")).order();
    assertThat(o.expiresAt()).isEqualTo(et("2026-10-09T16:00:00"));
    time.set(et("2026-10-09T16:00:00"));
    step();
    OrderDto x = order(p, o.orderId());
    assertThat(x.status()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(x.reservedQty()).isEqualByComparingTo("0");
  }

  @Test
  void cancellingAnArmedStopReleasesItsHoldAndItNeverTriggers() {
    Player p = human();
    String s = symbol();
    md.prevClose(s, "100.00");
    quote(s, "99.98", 500, "100.00", 500);
    OrderDto o = place(p, stop(cid(), s, Side.BUY, "2", "105.00", TimeInForce.GTC)).order();
    assertThat(accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE).buyingPower())
        .isEqualByComparingTo("285.80");
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 500, "100.00", 500);
    step();
    OrderDto c = orders.cancel(p.principal(), o.orderId(), meta());
    assertThat(c.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(c.reservedCash()).isEqualByComparingTo("0");
    assertThat(accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE).buyingPower())
        .isEqualByComparingTo("500.00");
    time.advance(Duration.ofSeconds(1));
    print(s, "106.00", 100);
    quote(s, "105.98", 500, "106.00", 500);
    step();
    OrderDto after = order(p, o.orderId());
    assertThat(after.triggered()).isFalse();
    assertThat(after.executions()).isEmpty();
    assertThat(md.leases().getOrDefault(s, java.util.Set.of())).doesNotContain("order:" + o.orderId());
  }

  @Test
  void aHaltedSymbolNeitherTriggersNorFillsAndAQuoteThroughTheStopTriggersAfterTheHalt() {
    String s = symbol();
    Player p = holder(s, 4);
    OrderDto o = place(p, stop(cid(), s, Side.SELL, "4", "95.00", TimeInForce.DAY)).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 1000, "100.00", 1000);
    print(s, "99.99", 100);
    step();
    Instant haltFrom = time.now().plusSeconds(1);
    md.halt(s, haltFrom, haltFrom.plusSeconds(60));
    time.set(haltFrom);
    print(s, "94.00", 100);
    quote(s, "93.95", 100, "94.00", 100);
    step();
    OrderDto halted = order(p, o.orderId());
    assertThat(halted.waitReason()).isEqualTo(WaitReason.HALTED);
    assertThat(halted.triggered()).isFalse();

    time.set(haltFrom.plusSeconds(61));
    quote(s, "93.90", 100, "93.95", 100);
    step();
    OrderDto t = order(p, o.orderId());
    assertThat(t.triggered()).isTrue();
    assertThat(trigger(t)).containsEntry("basis", "QUOTE");

    time.advance(Duration.ofSeconds(1));
    quote(s, "93.90", 100, "93.95", 100);
    step();
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.FILLED);
  }

  @Test
  void aRestartNeverTriggersOnWhatHappenedWhileTheEngineWasDown() {
    String s = symbol();
    Player p = holder(s, 6);
    OrderDto o = place(p, stop(cid(), s, Side.SELL, "6", "95.00", TimeInForce.GTC)).order();
    time.advance(Duration.ofSeconds(20));
    quote(s, "99.98", 1000, "100.00", 1000);
    step();
    assertThat(order(p, o.orderId()).waitReason()).isEqualTo(WaitReason.NOT_TRIGGERED);

    // the engine is down: the market dips through the stop and recovers, nobody evaluates
    time.advance(Duration.ofSeconds(1));
    md.print(s, time.now(), "94.00", 500);
    md.quote(s, time.now(), "93.95", 500, "94.00", 500);
    time.advance(Duration.ofSeconds(4));
    engine.restart();
    step();
    OrderDto afterRestart = order(p, o.orderId());
    assertThat(afterRestart.triggered()).as("the dip happened before the engine loaded the order").isFalse();
    assertThat(afterRestart.events()).extracting(OrderEventDto::type).doesNotContain(OrderEventType.TRIGGERED);

    time.advance(Duration.ofSeconds(1));
    quote(s, "99.50", 1000, "99.55", 1000);
    print(s, "99.52", 100);
    step();
    assertThat(order(p, o.orderId()).triggered()).isFalse();
    assertThat(order(p, o.orderId()).executions()).isEmpty();

    time.advance(Duration.ofSeconds(1));
    print(s, "94.90", 100);                                  // live data after the restart still triggers
    step();
    OrderDto t = order(p, o.orderId());
    assertThat(t.triggered()).isTrue();
    assertThat(dec(trigger(t).get("price"))).isEqualByComparingTo("94.90");
    time.advance(Duration.ofSeconds(1));
    quote(s, "94.85", 1000, "94.90", 1000);
    step();
    assertThat(order(p, o.orderId()).executions()).singleElement()
        .satisfies(x -> assertThat(x.executedAt()).isEqualTo(time.now()));
  }

  @Test
  void replacingAStopOrATrailCreditsTheOldHoldBackAndStartsFresh() {
    Player p = human();
    String s = symbol();
    md.prevClose(s, "100.00");
    print(s, "100.00", 100);
    quote(s, "99.98", 500, "100.00", 500);
    OrderDto o = place(p, stop(cid(), s, Side.BUY, "4", "105.00", TimeInForce.DAY)).order();
    assertThat(o.reservedCash()).isEqualByComparingTo("428.40");
    // 4 × 110 × 1.02 = 448.80 > 500 − 428.40 without the credit-back of the old hold
    PlaceOrderResponse moved = orders.replace(p.principal(), o.orderId(), new ReplaceOrderRequest(cid(), null, null,
        new BigDecimal("110.00"), null, null, null, null, null), meta());
    assertThat(moved.order().stopPrice()).isEqualByComparingTo("110.00");
    assertThat(moved.order().reservedCash()).isEqualByComparingTo("448.80");
    assertThat(moved.order().replacesOrderId()).isEqualTo(o.orderId());
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.REPLACED);

    assertThatThrownBy(() -> orders.replace(p.principal(), moved.order().orderId(), new ReplaceOrderRequest(cid(),
        null, null, new BigDecimal("99.00"), null, null, null, null, null), meta()))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.STOP_PRICE_WRONG_SIDE));
    assertThat(order(p, moved.order().orderId()).status()).as("a failed replace changes nothing")
        .isEqualTo(OrderStatus.PENDING);
    orders.cancel(p.principal(), moved.order().orderId(), meta());

    String h = symbol();
    Player q = holder(h, 5);
    OrderDto t = place(q, trailing(cid(), h, Side.SELL, "5", null, "5")).order();
    PlaceOrderResponse byAmount = orders.replace(q.principal(), t.orderId(), new ReplaceOrderRequest(cid(), null,
        null, null, new BigDecimal("3.00"), null, null, null, null), meta());
    assertThat(byAmount.order().trailAmount()).isEqualByComparingTo("3.00");
    assertThat(byAmount.order().trailPercent()).as("the trail is replaced as a pair").isNull();
    assertThat(byAmount.order().type()).isEqualTo(OrderType.TRAILING_STOP);
    assertThat(byAmount.order().reservedQty()).isEqualByComparingTo("5");
  }

  // ------------------------------------------------------------------------------------------- helpers

  /** A player holding {@code shares} of {@code s} bought at $100.00, with consolidated prints at $100.00. */
  private Player holder(String s, int shares) {
    Player p = human("5000.00");
    quote(s, "99.98", 1000, "100.00", 1000);
    print(s, "100.00", 100);
    OrderDto buy = place(p, market(cid(), s, Side.BUY, String.valueOf(shares))).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 1000, "100.00", 1000);
    print(s, "100.00", 100);
    step();
    assertThat(order(p, buy.orderId()).status()).isEqualTo(OrderStatus.FILLED);
    return p;
  }

  private static Map<String, Object> trigger(OrderDto o) {
    List<OrderEventDto> t = o.events().stream().filter(e -> e.type() == OrderEventType.TRIGGERED).toList();
    assertThat(t).as("exactly one TRIGGERED event").hasSize(1);
    return t.get(0).detail();
  }

  private static BigDecimal dec(Object v) {
    return new BigDecimal(String.valueOf(v));
  }
}
