package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.trading.Balances;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.testkit.TestTimes;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MARKET orders end to end on the engine (§11, §12.4, §12.8, §10.2–§10.3): holds, the anti-look-ahead latency window,
 * fills at the touch, the book walk, the collar, notional all-in buys, sells with SEC fees and realized P&L, T+1
 * settlement in the balances, and the BUY execution-time guard.
 */
class MarketOrderScenarioTest extends TradingTestBase {

  @Test
  void marketBuyHoldsThenFillsAtTheAskAfterLatency() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);

    PlaceOrderResponse r = place(p, market(cid(), s, Side.BUY, "2"));
    OrderDto o = r.order();
    assertThat(o.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(o.waitReason()).isEqualTo(WaitReason.LATENCY);
    assertThat(o.reservedCash()).isEqualByComparingTo("204.00");          // 2 × 100.00 × 1.02 + CAT 0
    assertThat(r.buyingPowerBefore()).isEqualByComparingTo("500.00");
    assertThat(r.buyingPowerAfter()).isEqualByComparingTo("296.00");
    assertThat(Duration.between(o.acceptedAt(), o.eligibleAt()).toMillis()).isBetween(350L, 750L);

    // the quote seen at acceptance is not executable (anti-look-ahead): nothing fills before a fresh one
    step();
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.PENDING);
    time.advance(Duration.ofSeconds(1));
    step();
    OrderDto waiting = order(p, o.orderId());
    assertThat(waiting.status()).isEqualTo(OrderStatus.WORKING);
    assertThat(waiting.waitReason()).isEqualTo(WaitReason.AWAITING_QUOTE);

    quote(s, "100.03", 300, "100.05", 300);
    step();
    OrderDto filled = order(p, o.orderId());
    assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(filled.filledQty()).isEqualByComparingTo("2");
    assertThat(filled.avgFillPrice()).isEqualByComparingTo("100.05");
    assertThat(filled.reservedCash()).isEqualByComparingTo("0");
    assertThat(filled.collarPrice()).isEqualByComparingTo("105.06");     // TICK_UP(100.05 × 1.05)
    assertThat(filled.events()).extracting(e -> e.type())
        .containsSubsequence(OrderEventType.ACCEPTED, OrderEventType.WORKING, OrderEventType.AWAITING_QUOTE,
            OrderEventType.FILL);
    ExecutionDto x = filled.executions().get(0);
    assertThat(x.liquidity()).isEqualTo(Liquidity.TOUCH);
    assertThat(x.principal()).isEqualByComparingTo("200.10");
    assertThat(x.netAmount()).isEqualByComparingTo("-200.10");
    assertThat(x.tradeDate()).isEqualTo(TestTimes.TRADE_DATE);
    assertThat(x.settleDate()).isEqualTo(TestTimes.SETTLE_DATE);         // Columbus Day skipped

    Balances b = accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE);
    assertThat(b.cashTotal()).isEqualByComparingTo("299.90");
    assertThat(b.settledCash()).isEqualByComparingTo("500.00");
    assertThat(b.unsettledCash()).isEqualByComparingTo("-200.10");
    assertThat(b.unsettledDebits()).isEqualByComparingTo("200.10");
    assertThat(b.openBuyHolds()).isEqualByComparingTo("0");
    assertThat(b.buyingPower()).isEqualByComparingTo("299.90");
    assertThat(accountQueries.balances(p.accountId(), TestTimes.SETTLE_DATE).settledCash())
        .isEqualByComparingTo("299.90");

    List<PositionDto> positions = accountQueries.positions(p.accountId());
    assertThat(positions).singleElement().satisfies(pos -> {
      assertThat(pos.symbol()).isEqualTo(s);
      assertThat(pos.qty()).isEqualByComparingTo("2");
      assertThat(pos.costBasis()).isEqualByComparingTo("200.10");
      assertThat(pos.avgCost()).isEqualByComparingTo("100.05");
    });
  }

  @Test
  void aLargeMarketBuyWalksTheBookUpToTheCollar() {
    Player p = human("100000.00");
    String s = symbol();
    quote(s, "49.98", 100, "50.00", 100);
    OrderDto o = place(p, market(cid(), s, Side.BUY, "350")).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, "49.98", 100, "50.00", 100);
    step();
    OrderDto f = order(p, o.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    // step = max(tick, ½ spread on tick) = 0.01; level 0 shows 100, deeper levels max(shown, 100) = 100
    assertThat(f.executions()).extracting(ExecutionDto::price).extracting(BigDecimal::toPlainString)
        .containsExactlyInAnyOrder("50.0000", "50.0100", "50.0200", "50.0300");
    assertThat(f.executions()).extracting(ExecutionDto::qty).extracting(BigDecimal::stripTrailingZeros)
        .extracting(BigDecimal::toPlainString).containsExactlyInAnyOrder("100", "100", "100", "50");
    assertThat(f.executions()).filteredOn(x -> x.liquidity() == Liquidity.WALK).hasSize(3);
  }

  @Test
  void aMarketRemainderBeyondTheCollarWaitsAndFillsLater() {
    Player p = human("100000.00");
    String s = symbol();
    quote(s, "9.00", 100, "10.00", 100);             // wide: step = 0.50, collar = TICK_UP(10.50) = 10.50
    OrderDto o = place(p, market(cid(), s, Side.BUY, "500")).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, "9.00", 100, "10.00", 100);
    step();
    OrderDto partial = order(p, o.orderId());
    assertThat(partial.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(partial.filledQty()).isEqualByComparingTo("200");         // 10.00 and 10.50; 11.00 > collar
    assertThat(partial.waitReason()).isEqualTo(WaitReason.BEYOND_COLLAR);
    assertThat(partial.collarPrice()).isEqualByComparingTo("10.50");
    assertThat(partial.events()).extracting(e -> e.type()).contains(OrderEventType.BEYOND_COLLAR);

    time.advance(Duration.ofSeconds(2));
    quote(s, "10.10", 1000, "10.20", 1000);
    step();
    OrderDto done = order(p, o.orderId());
    assertThat(done.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(done.filledQty()).isEqualByComparingTo("500");
    assertThat(done.reservedCash()).isEqualByComparingTo("0");
  }

  @Test
  void notionalBuyIsAllInAndCanSpendTheWholeAccount() {
    Player p = human();
    String s = symbol();
    quote(s, "227.40", 300, "227.42", 300);
    PlaceOrderResponse r = place(p, notional(cid(), s, Side.BUY, "500.00"));
    assertThat(r.order().fractional()).isTrue();
    assertThat(r.order().reservedCash()).isEqualByComparingTo("500.00");
    assertThat(r.buyingPowerAfter()).isEqualByComparingTo("0.00");
    time.advance(Duration.ofSeconds(1));
    quote(s, "227.40", 300, "227.42", 300);
    step();
    OrderDto f = order(p, r.order().orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions()).singleElement().satisfies(x -> {
      assertThat(x.qty()).isEqualByComparingTo("2.19857");               // floor5(500 / 227.42)
      assertThat(x.price()).isEqualByComparingTo("227.42");
      assertThat(x.principal().add(x.fees().total())).isLessThanOrEqualTo(new BigDecimal("500.00"));
    });
    assertThat(f.qty()).as("qty XOR notional (ck_order_qty_xor_notional)").isNull();
    assertThat(f.filledQty()).isEqualByComparingTo("2.19857");
    Balances b = accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE);
    assertThat(b.cashTotal()).isGreaterThanOrEqualTo(BigDecimal.ZERO).isLessThan(new BigDecimal("0.01"));
  }

  @Test
  void sellChargesSecFeeAndRealizesPnl() {
    Player p = human();
    String s = symbol();
    buyNow(p, s, "3", "100.00");
    quote(s, "110.00", 500, "110.02", 500);
    OrderDto sell = place(p, market(cid(), s, Side.SELL, "3")).order();
    assertThat(sell.reservedQty()).isEqualByComparingTo("3");
    assertThat(sell.reservedCash()).isEqualByComparingTo("0");
    time.advance(Duration.ofSeconds(1));
    quote(s, "110.00", 500, "110.02", 500);
    step();
    OrderDto f = order(p, sell.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    ExecutionDto x = f.executions().get(0);
    assertThat(x.principal()).isEqualByComparingTo("330.00");
    assertThat(x.fees().sec()).isEqualByComparingTo("0.01");             // CEIL_CENT(330 × 0.0000206)
    assertThat(x.netAmount()).isEqualByComparingTo("329.99");
    assertThat(x.realizedPnl()).isEqualByComparingTo("29.99");
    assertThat(accountQueries.positions(p.accountId())).isEmpty();
    Balances b = accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE);
    assertThat(b.cashTotal()).isEqualByComparingTo("529.99");
    assertThat(b.unsettledCredits()).isEqualByComparingTo("329.99");
    assertThat(b.settledAvailable()).isEqualByComparingTo("200.00");
    assertThat(b.buyingPower()).isEqualByComparingTo("529.99");          // Webull: proceeds usable at once
    assertThat(b.withdrawable()).isEqualByComparingTo("200.00");
  }

  @Test
  void marketOrdersAfterTheCloseQueueForTheNextOpenWith115PercentHolds() {
    Player p = human();
    String s = symbol();
    time.set(et("2026-10-09T17:00:00"));
    md.prevClose(s, "100.00");
    print(s, "100.00", 100);
    PlaceOrderResponse r = place(p, market(cid(), s, Side.BUY, "4"));
    OrderDto o = r.order();
    assertThat(o.waitReason()).isEqualTo(WaitReason.QUEUED_FOR_OPEN);
    assertThat(o.reservedCash()).isEqualByComparingTo("460.00");          // 4 × 100 × 1.15
    assertThat(o.sessionDate()).isEqualTo(TestTimes.COLUMBUS_DAY);        // NYSE open on Columbus Day
    assertThat(o.eligibleAt()).isAfter(et("2026-10-12T09:30:00"));
    assertThat(o.events()).as("detail only on the detail endpoints").isNull();
    assertThat(order(p, o.orderId()).events()).extracting(e -> e.type())
        .containsExactly(OrderEventType.ACCEPTED, OrderEventType.QUEUED);

    time.set(et("2026-10-12T09:30:01"));
    quote(s, "101.00", 300, "101.02", 300);
    step();
    OrderDto f = order(p, o.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(f.executions()).singleElement().satisfies(x -> {
      assertThat(x.liquidity()).isEqualTo(Liquidity.OPEN);
      assertThat(x.tradeDate()).isEqualTo(TestTimes.COLUMBUS_DAY);
      assertThat(x.settleDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    });
  }

  @Test
  void aBuyTheAccountCannotAffordIsRejectedAndNotPersisted() {
    Player p = human();
    String s = symbol();
    quote(s, "199.98", 300, "200.00", 300);
    String cid = cid();
    assertThatThrownBy(() -> place(p, market(cid, s, Side.BUY, "3")))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_BUYING_POWER);
          assertThat(e.status()).isEqualTo(422);
          assertThat(e.hint()).contains("2");
        });
    assertThat(orderQueries.byClientOrderId(p.accountId(), cid, false)).isEmpty();
    // the clientOrderId stays free for the corrected retry (D15)
    assertThat(place(p, market(cid, s, Side.BUY, "2")).order().status()).isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void theExecutionTimeGuardFillsWhatIsAffordableAndCancelsTheRest() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    OrderDto o = place(p, market(cid(), s, Side.BUY, "4")).order();       // hold 408.00 of 500.00
    time.advance(Duration.ofSeconds(1));
    quote(s, "129.98", 300, "130.00", 300);                                // gaps up: 4 × 130 = 520 > 500
    step();
    OrderDto f = order(p, o.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(f.statusReason()).isEqualTo("INSUFFICIENT_BUYING_POWER_AT_EXECUTION");
    assertThat(f.filledQty()).isEqualByComparingTo("3");
    assertThat(accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE).cashTotal())
        .isEqualByComparingTo("110.00");
  }

  @Test
  void sellingMoreThanHeldIsRejected() {
    Player p = human();
    String s = symbol();
    quote(s, "9.99", 300, "10.00", 300);
    assertThatThrownBy(() -> place(p, market(cid(), s, Side.SELL, "1")))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.SHORT_SELLING_NOT_ALLOWED));
    buyNow(p, s, "2", "10.00");
    place(p, market(cid(), s, Side.SELL, "1"));
    assertThatThrownBy(() -> place(p, market(cid(), s, Side.SELL, "2")))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_POSITION));
  }

  /** A filled MARKET buy at {@code ask} (the order is placed, then a fresh quote fills it). */
  private void buyNow(Player p, String s, String qty, String ask) {
    BigDecimal a = new BigDecimal(ask);
    String bid = a.subtract(new BigDecimal("0.02")).toPlainString();
    quote(s, bid, 1000, ask, 1000);
    OrderDto o = place(p, market(cid(), s, Side.BUY, qty)).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, bid, 1000, ask, 1000);
    step();
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.FILLED);
    time.advance(Duration.ofSeconds(1));
  }
}
