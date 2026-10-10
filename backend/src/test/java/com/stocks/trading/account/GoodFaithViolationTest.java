package com.stocks.trading.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.ViolationIssued;
import com.stocks.contract.trading.ActivityFilter;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.testkit.TestTimes;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * T+1 settled cash and good-faith violations in a CASH account (§10.2, §10.4): funding attribution on buys, the
 * GFV_RISK prediction on the order's session date with the required acknowledgement, detection at execution, and no
 * violation once the funding proceeds have settled.
 */
@RecordApplicationEvents
class GoodFaithViolationTest extends TradingTestBase {

  @Autowired ApplicationEvents events;

  @Test
  void sellingSharesBoughtWithUnsettledProceedsBeforeTheySettleIsAGfv() {
    Player p = human();
    String a = symbol();
    String b = symbol();
    trade(p, a, Side.BUY, "4", "100.00");                    // settled cash pays 400
    trade(p, a, Side.SELL, "4", "100.00");                   // proceeds 399.99 settle Tue 10/13
    var bal = accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE);
    assertThat(bal.unsettledCredits()).isEqualByComparingTo("399.99");
    assertThat(bal.settledAvailable()).isEqualByComparingTo("100.00");
    trade(p, b, Side.BUY, "3", "100.00");                    // 100 settled + 200 unsettled
    assertThat(jdbc.sql("SELECT unsettled_funding FROM position_lot WHERE account_id = ? AND symbol = ?")
        .params(p.accountId(), b).query(BigDecimal.class).single()).isEqualByComparingTo("200.00");
    assertThat(jdbc.sql("SELECT funding_settle_date FROM position_lot WHERE account_id = ? AND symbol = ?")
        .params(p.accountId(), b).query(LocalDate.class).single()).isEqualTo(TestTimes.SETTLE_DATE);

    // Monday (Columbus Day) is before the funding settles: the sell needs the acknowledgement
    time.set(et("2026-10-12T10:00:00"));
    quote(b, "101.00", 1000, "101.02", 1000);
    assertThatThrownBy(() -> place(p, market(cid(), b, Side.SELL, "3")))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCode.GFV_ACK_REQUIRED);
          assertThat(e.status()).isEqualTo(422);
        });
    assertThat(orders.preview(p.principal(), market(null, b, Side.SELL, "3")).errors())
        .extracting(e -> e.code()).containsExactly("GFV_ACK_REQUIRED");
    PlaceOrderResponse r = place(p, new PlaceOrderRequest(cid(), b, Side.SELL,
        com.stocks.common.domain.OrderType.MARKET, new BigDecimal("3"), null, null, null, null, null,
        com.stocks.common.domain.TimeInForce.DAY, false, "taking profit", null, Set.of("GFV_RISK")));
    assertThat(r.warnings()).extracting(w -> w.code()).contains("GFV_RISK");
    time.advance(Duration.ofSeconds(1));
    quote(b, "101.00", 1000, "101.02", 1000);
    step();
    assertThat(order(p, r.order().orderId()).status()).isEqualTo(OrderStatus.FILLED);

    assertThat(accountQueries.violations(p.accountId())).singleElement().satisfies(v -> {
      assertThat(v.symbol()).isEqualTo(b);
      assertThat(v.unsettledAmount()).isEqualByComparingTo("200.00");
      assertThat(v.fundingSettleDate()).isEqualTo(TestTimes.SETTLE_DATE);
      assertThat(v.orderId()).isEqualTo(r.order().orderId());
    });
    assertThat(account(p).gfv().active()).isEqualTo(1);
    assertThat(accountQueries.activities(p.accountId(), new ActivityFilter(Set.of(ActivityType.GFV), null, null), null,
        10).items()).hasSize(1);
    assertThat(events.stream(ViolationIssued.class).filter(e -> e.accountId() == p.accountId())).hasSize(1);
  }

  @Test
  void noViolationOnceTheFundingHasSettled() {
    Player p = human();
    String a = symbol();
    String b = symbol();
    trade(p, a, Side.BUY, "4", "100.00");
    trade(p, a, Side.SELL, "4", "100.00");
    trade(p, b, Side.BUY, "3", "100.00");
    time.set(et("2026-10-13T10:00:00"));                     // the proceeds settled at 00:00 ET
    quote(b, "101.00", 1000, "101.02", 1000);
    PlaceOrderResponse r = place(p, market(cid(), b, Side.SELL, "3"));
    assertThat(r.warnings()).extracting(w -> w.code()).doesNotContain("GFV_RISK");
    time.advance(Duration.ofSeconds(1));
    quote(b, "101.00", 1000, "101.02", 1000);
    step();
    assertThat(order(p, r.order().orderId()).status()).isEqualTo(OrderStatus.FILLED);
    assertThat(accountQueries.violations(p.accountId())).isEmpty();
    var bal = accountQueries.balances(p.accountId(), LocalDate.of(2026, 10, 13));
    assertThat(bal.unsettledCredits()).isEqualByComparingTo("302.99");    // 3 × 101.00 − 0.01 SEC, settles 10/14
  }

  @Test
  void aSellQueuedOnSaturdayIsPredictedOnItsSessionDate() {
    Player p = human();
    String a = symbol();
    String b = symbol();
    trade(p, a, Side.BUY, "4", "100.00");
    trade(p, a, Side.SELL, "4", "100.00");
    trade(p, b, Side.BUY, "3", "100.00");
    time.set(et("2026-10-10T12:00:00"));                     // Saturday: session_date is Monday 10/12 < 10/13
    assertThatThrownBy(() -> place(p, market(cid(), b, Side.SELL, "3")))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.GFV_ACK_REQUIRED));
  }

  /** A MARKET order filled at {@code price} (bid = ask − 0.02 for buys; the sell fills at the bid = price). */
  private void trade(Player p, String s, Side side, String qty, String price) {
    BigDecimal px = new BigDecimal(price);
    String bid = side == Side.BUY ? px.subtract(new BigDecimal("0.02")).toPlainString() : price;
    String ask = side == Side.BUY ? price : px.add(new BigDecimal("0.02")).toPlainString();
    quote(s, bid, 1000, ask, 1000);
    OrderDto o = place(p, market(cid(), s, side, qty)).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, bid, 1000, ask, 1000);
    step();
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.FILLED);
    time.advance(Duration.ofSeconds(1));
  }
}
