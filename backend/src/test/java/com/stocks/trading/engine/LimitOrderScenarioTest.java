package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.WarningCode;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.ReplaceOrderRequest;
import com.stocks.testkit.TestTimes;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * LIMIT orders on the engine (§11.1, §11.3–§11.5, §12.4–§12.6): marketable on arrival (price improvement), resting
 * fills on a quote cross or a trade-through print (never on a print exactly at L), DAY expiry, GTC pause/resume with
 * arriving semantics, and extended-hours limits in PRE/POST.
 */
class LimitOrderScenarioTest extends TradingTestBase {

  @Test
  void aMarketableLimitFillsAtTheTouchWithPriceImprovement() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    PlaceOrderResponse r = place(p, limit(cid(), s, Side.BUY, "2", "101.00", TimeInForce.DAY, false));
    assertThat(r.order().reservedCash()).isEqualByComparingTo("202.00");          // qty × L + CAT 0
    assertThat(r.estimate().price()).isEqualByComparingTo("100.00");
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 300, "100.00", 300);
    step();
    OrderDto f = order(p, r.order().orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions()).singleElement().satisfies(x -> {
      assertThat(x.price()).isEqualByComparingTo("100.00");
      assertThat(x.liquidity()).isEqualTo(Liquidity.TOUCH);
    });
  }

  @Test
  void aRestingBuyFillsAtItsLimitWhenTheAskCrosses() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    OrderDto o = place(p, limit(cid(), s, Side.BUY, "3", "95.00", TimeInForce.DAY, false)).order();
    assertThat(o.reservedCash()).isEqualByComparingTo("285.00");
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 300, "100.00", 300);
    step();
    OrderDto resting = order(p, o.orderId());
    assertThat(resting.status()).isEqualTo(OrderStatus.WORKING);
    assertThat(resting.waitReason()).isEqualTo(WaitReason.LIMIT_NOT_REACHED);
    assertThat(resting.filledQty()).isEqualByComparingTo("0");

    time.advance(Duration.ofSeconds(2));
    quote(s, "94.80", 100, "94.90", 2);                // ask crosses L: fills max(size, 100) at L
    step();
    OrderDto f = order(p, o.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions()).singleElement().satisfies(x -> {
      assertThat(x.price()).isEqualByComparingTo("95.00");
      assertThat(x.qty()).isEqualByComparingTo("3");
      assertThat(x.liquidity()).isEqualTo(Liquidity.RESTING);
    });
    assertThat(f.reservedCash()).isEqualByComparingTo("0");
    assertThat(accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE).cashTotal())
        .isEqualByComparingTo("215.00");
  }

  @Test
  void aRestingSellFillsOnPrintsStrictlyThroughTheLimitOnly() {
    Player p = human("6000.00");
    String s = symbol();
    quote(s, "49.98", 1000, "50.00", 1000);
    OrderDto buy = place(p, market(cid(), s, Side.BUY, "100")).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, "49.98", 1000, "50.00", 1000);
    step();
    assertThat(order(p, buy.orderId()).status()).isEqualTo(OrderStatus.FILLED);

    OrderDto sell = place(p, limit(cid(), s, Side.SELL, "100", "51.00", TimeInForce.DAY, false)).order();
    assertThat(sell.reservedQty()).isEqualByComparingTo("100");
    time.advance(Duration.ofSeconds(1));
    quote(s, "49.98", 1000, "50.00", 1000);
    step();
    assertThat(order(p, sell.orderId()).waitReason()).isEqualTo(WaitReason.LIMIT_NOT_REACHED);

    time.advance(Duration.ofSeconds(2));
    print(s, "51.00", 500);                            // exactly at L: queue position unknown, ignored
    step();
    assertThat(order(p, sell.orderId()).filledQty()).isEqualByComparingTo("0");

    time.advance(Duration.ofSeconds(2));
    print(s, "51.05", 40);                             // strictly through L
    step();
    OrderDto partial = order(p, sell.orderId());
    assertThat(partial.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(partial.filledQty()).isEqualByComparingTo("40");
    assertThat(partial.reservedQty()).isEqualByComparingTo("60");
    assertThat(partial.executions()).singleElement().satisfies(x -> {
      assertThat(x.price()).isEqualByComparingTo("51.00");
      assertThat(x.liquidity()).isEqualTo(Liquidity.PRINT);
    });
    assertThat(accountQueries.positions(p.accountId())).singleElement()
        .satisfies(pos -> assertThat(pos.qty()).isEqualByComparingTo("60"));
  }

  @Test
  void aDayLimitExpiresAtTheCloseAndReleasesItsHold() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    OrderDto o = place(p, limit(cid(), s, Side.BUY, "2", "90.00", TimeInForce.DAY, false)).order();
    assertThat(o.expiresAt()).isEqualTo(et("2026-10-09T16:00:00"));
    assertThat(accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE).openBuyHolds())
        .isEqualByComparingTo("180.00");
    time.set(et("2026-10-09T16:00:00"));
    step();
    OrderDto x = order(p, o.orderId());
    assertThat(x.status()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(x.reservedCash()).isEqualByComparingTo("0");
    assertThat(x.events()).extracting(e -> e.type()).endsWith(OrderEventType.EXPIRED);
    assertThat(accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE).buyingPower())
        .isEqualByComparingTo("500.00");
  }

  @Test
  void aGtcLimitPausesAtTheCloseAndResumesWithArrivingSemantics() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    OrderDto o = place(p, limit(cid(), s, Side.BUY, "2", "98.00", TimeInForce.GTC, false)).order();
    // today + 60 days = Tue 2026-12-08, a trading day; non-EXT GTC ends at its regular close
    assertThat(o.expiresAt()).isEqualTo(et("2026-12-08T16:00:00"));
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 300, "100.00", 300);
    step();
    assertThat(order(p, o.orderId()).waitReason()).isEqualTo(WaitReason.LIMIT_NOT_REACHED);

    time.set(et("2026-10-09T16:00:01"));
    step();
    OrderDto paused = order(p, o.orderId());
    assertThat(paused.status()).isEqualTo(OrderStatus.WORKING);
    assertThat(paused.waitReason()).isEqualTo(WaitReason.SESSION_CLOSED);
    assertThat(paused.events()).extracting(e -> e.type()).contains(OrderEventType.PAUSED);

    // the next regular session: Columbus Day (NYSE open); the gap down is marketable on arrival → min(L, ask)
    time.set(et("2026-10-12T09:30:00"));
    step();
    OrderDto resumed = order(p, o.orderId());
    assertThat(resumed.events()).extracting(e -> e.type()).contains(OrderEventType.RESUMED);
    assertThat(resumed.eligibleAt()).isAfter(et("2026-10-12T09:30:00"));
    time.set(et("2026-10-12T09:30:01"));
    quote(s, "96.98", 300, "97.00", 300);
    step();
    OrderDto f = order(p, o.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions()).singleElement().satisfies(x -> {
      assertThat(x.price()).isEqualByComparingTo("97.00");
      assertThat(x.tradeDate()).isEqualTo(LocalDate.of(2026, 10, 12));
    });
  }

  @Test
  void anExtendedHoursLimitWorksInThePostSession() {
    Player p = human();
    String s = symbol();
    time.set(et("2026-10-09T16:30:00"));
    print(s, "100.00", 100);
    quote(s, "99.90", 100, "100.10", 100);
    PlaceOrderResponse r = place(p, limit(cid(), s, Side.BUY, "1", "100.20", TimeInForce.DAY, true));
    OrderDto o = r.order();
    assertThat(o.waitReason()).isEqualTo(WaitReason.LATENCY);
    assertThat(o.sessionDate()).isEqualTo(TestTimes.TRADE_DATE);
    assertThat(o.expiresAt()).isEqualTo(et("2026-10-09T20:00:00"));      // EXT DAY ends at the post close
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.90", 100, "100.10", 100);
    step();
    OrderDto f = order(p, o.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions().get(0).price()).isEqualByComparingTo("100.10");
  }

  @Test
  void aRegularHoursLimitEnteredAfterTheCloseQueuesForTheOpen() {
    Player p = human();
    String s = symbol();
    time.set(et("2026-10-10T11:00:00"));             // Saturday
    md.prevClose(s, "50.00");
    PlaceOrderResponse r = place(p, limit(cid(), s, Side.BUY, "1", "49.00", TimeInForce.DAY, false));
    assertThat(r.order().waitReason()).isEqualTo(WaitReason.QUEUED_FOR_OPEN);
    assertThat(r.order().sessionDate()).isEqualTo(TestTimes.COLUMBUS_DAY);
    PlaceOrderResponse ext = place(p, limit(cid(), s, Side.BUY, "1", "49.00", TimeInForce.DAY, true));
    assertThat(ext.order().waitReason()).isEqualTo(WaitReason.QUEUED_FOR_EXT_OPEN);
    assertThat(ext.order().eligibleAt()).isAfter(et("2026-10-12T04:00:00")).isBefore(et("2026-10-12T04:00:01"));
    assertThat(ext.order().expiresAt()).isEqualTo(et("2026-10-12T20:00:00"));
  }

  @Test
  void aLimitFarFromTheMarketWarnsAboutAFatFinger() {
    Player p = human();
    String s = symbol();
    print(s, "100.00", 100);
    quote(s, "99.98", 300, "100.00", 300);
    PlaceOrderResponse r = place(p, limit(cid(), s, Side.BUY, "1", "80.00", TimeInForce.DAY, false));
    assertThat(r.warnings()).extracting(w -> w.code()).contains(WarningCode.FAT_FINGER.name());
  }

  @Test
  void partialFillsReduceTheHoldOnTheLimitBasis() {
    Player p = human("10000.00");
    String s = symbol();
    quote(s, "19.98", 100, "20.00", 100);
    OrderDto o = place(p, limit(cid(), s, Side.BUY, "300", "20.00", TimeInForce.DAY, false)).order();
    assertThat(o.reservedCash()).isEqualByComparingTo("6000.00");
    time.advance(Duration.ofSeconds(1));
    quote(s, "19.98", 100, "20.00", 100);               // touch only: 100 shown at 20.00; 20.01 > L
    step();
    OrderDto partial = order(p, o.orderId());
    assertThat(partial.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(partial.filledQty()).isEqualByComparingTo("100");
    assertThat(partial.reservedCash()).isEqualByComparingTo("4000.00");
    assertThat(partial.executions()).extracting(ExecutionDto::liquidity).containsExactly(Liquidity.TOUCH);
  }

  // ------------------------------------------------------------------------------------------- public mode (§16.7)

  boolean rationalePublic(String orderId) {
    return jdbc.sql("SELECT rationale_public FROM trade_order WHERE public_id = ?").param(orderId)
        .query(Boolean.class).single();
  }

  String rationale(String orderId) {
    return jdbc.sql("SELECT rationale FROM trade_order WHERE public_id = ?").param(orderId).query(String.class)
        .single();
  }

  @Test
  void aHumansNewNoteIsPublicAndAReplaceKeepsAnOldPrivateNotePrivate() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    OrderDto o = place(p, limit(cid(), s, Side.BUY, "1", "95.00", TimeInForce.DAY, false)).order();
    assertThat(rationalePublic(o.orderId())).as("no shareNote: public anyway").isTrue();

    // a note written as private before public mode (V9 keeps the stored flag)
    jdbc.sql("UPDATE trade_order SET rationale_public = FALSE WHERE public_id = ?").param(o.orderId()).update();
    OrderDto moved = orders.replace(p.principal(), o.orderId(), new ReplaceOrderRequest(cid(), null,
        new BigDecimal("94.00"), null, null, null, null, null, null), meta()).order();
    assertThat(rationalePublic(moved.orderId())).as("a price change never publishes an old private note").isFalse();
    assertThat(rationale(moved.orderId())).isEqualTo("test");
    OrderDto blank = orders.replace(p.principal(), moved.orderId(), new ReplaceOrderRequest(cid(), null,
        new BigDecimal("93.00"), null, null, null, null, null, "   "), meta()).order();
    assertThat(rationalePublic(blank.orderId())).as("a blank note is no new note").isFalse();
    assertThat(rationale(blank.orderId())).isEqualTo("test");

    OrderDto renoted = orders.replace(p.principal(), blank.orderId(), new ReplaceOrderRequest(cid(), null,
        new BigDecimal("92.00"), null, null, null, null, null, " Waiting for a dip "), meta()).order();
    assertThat(rationalePublic(renoted.orderId())).as("a new note is public").isTrue();
    assertThat(rationale(renoted.orderId())).isEqualTo("Waiting for a dip");
    OrderDto again = orders.replace(p.principal(), renoted.orderId(), new ReplaceOrderRequest(cid(), null,
        new BigDecimal("91.00"), null, null, null, null, null, null), meta()).order();
    assertThat(rationalePublic(again.orderId())).as("a public note stays public").isTrue();
  }
}
