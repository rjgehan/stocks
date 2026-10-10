package com.stocks.trading.corpactions;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.contract.trading.dto.GroupLegRequest;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderEventDto;
import com.stocks.contract.trading.dto.PlaceOrderGroupRequest;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * FINRA Rule 5330 on the ex-date of a cash dividend (§14, Phase 2): a BUY LIMIT and an untriggered SELL STOP or
 * STOP_LIMIT entered before the ex cut-off are reduced by the dividend, rounded down to the tick; a SELL LIMIT, a BUY
 * STOP and orders marked "do not reduce" keep their prices; an order entered after the cut-off was priced ex-dividend;
 * a price that would reach zero cancels the order; a BUY LIMIT's hold follows its new limit; held group children are
 * adjusted too.
 */
class DividendOrderAdjustmentTest extends CorporateActionTestSupport {

  @Test
  void openOrdersAreReducedByTheDividendRoundedDownToTheTick() {
    Player p = human("10000.00");
    String s = symbol();
    buy(p, s, "20", "25.10");
    md.prevClose(s, "25.10");
    print(s, "25.10", 100);
    OrderDto buyLimit = gtc(p, limit(cid(), s, Side.BUY, "10", "25.03", TimeInForce.GTC, false));
    OrderDto sellStop = gtc(p, stop(cid(), s, Side.SELL, "5", "24.00", TimeInForce.GTC));
    OrderDto sellStopLimit = gtc(p, stopLimit(cid(), s, Side.SELL, "5", "24.50", "24.40", TimeInForce.GTC));
    OrderDto sellLimit = gtc(p, limit(cid(), s, Side.SELL, "5", "30.00", TimeInForce.GTC, false));
    OrderDto buyStop = gtc(p, stop(cid(), s, Side.BUY, "1", "27.00", TimeInForce.GTC));
    OrderDto keep = gtc(p, new PlaceOrderRequest(cid(), s, Side.BUY, OrderType.LIMIT, new BigDecimal("2"), null,
        new BigDecimal("24.00"), null, null, null, TimeInForce.GTC, false, "test", null, Set.of(), true));
    assertThat(keep.doNotReduce()).isTrue();
    assertThat(buyLimit.reservedCash()).isEqualByComparingTo("250.30");

    String id = record(s, CorporateActionType.CASH_DIVIDEND, MON, null, null, "0.125", WED, null);
    at0345(MON);
    apply(id);

    OrderDto bl = order(p, buyLimit.orderId());
    assertThat(bl.limitPrice()).as("25.03 − 0.125 = 24.905 → 24.90").isEqualByComparingTo("24.90");
    assertThat(bl.reservedCash()).isEqualByComparingTo("249.00");
    Map<String, Object> detail = event(bl, OrderEventType.PRICE_ADJUSTED);
    assertThat(detail).containsEntry("reason", "DIVIDEND").containsEntry("corporateActionId", id)
        .containsEntry("dividend", "0.125");
    assertThat(new BigDecimal(String.valueOf(detail.get("limitBefore")))).isEqualByComparingTo("25.03");
    assertThat(new BigDecimal(String.valueOf(detail.get("limitAfter")))).isEqualByComparingTo("24.90");

    assertThat(order(p, sellStop.orderId()).stopPrice()).isEqualByComparingTo("23.87");
    OrderDto ssl = order(p, sellStopLimit.orderId());
    assertThat(ssl.stopPrice()).isEqualByComparingTo("24.37");
    assertThat(ssl.limitPrice()).isEqualByComparingTo("24.27");
    assertThat(order(p, sellLimit.orderId()).limitPrice()).as("sell limits are not reduced")
        .isEqualByComparingTo("30.00");
    assertThat(order(p, buyStop.orderId()).stopPrice()).as("buy stops are not reduced").isEqualByComparingTo("27.00");
    OrderDto kept = order(p, keep.orderId());
    assertThat(kept.limitPrice()).as("do not reduce").isEqualByComparingTo("24.00");
    assertThat(kept.events()).extracting(OrderEventDto::type).doesNotContain(OrderEventType.PRICE_ADJUSTED);

    // the job may replay the action: an APPLIED action changes nothing again
    apply(id);
    assertThat(order(p, buyLimit.orderId()).limitPrice()).isEqualByComparingTo("24.90");
  }

  @Test
  void ordersEnteredAfterTheCutoffAndPricesThatWouldReachZeroAreHandled() {
    Player p = human("1000.00");
    String s = symbol();
    md.prevClose(s, "0.50");
    quote(s, "0.49", 1000, "0.50", 1000);
    print(s, "0.50", 100);
    OrderDto tiny = gtc(p, limit(cid(), s, Side.BUY, "100", "0.40", TimeInForce.GTC, false));
    String id = record(s, CorporateActionType.CASH_DIVIDEND, MON, null, null, "0.45", WED, null);
    at0345(MON);
    time.set(et("2026-10-12T04:30:00"));                // after the ex cut-off (the pre-open)
    OrderDto late = place(p, limit(cid(), s, Side.BUY, "100", "0.45", TimeInForce.GTC, true)).order();
    apply(id);
    OrderDto t = order(p, tiny.orderId());
    assertThat(t.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(t.statusReason()).isEqualTo("CORPORATE_ACTION");
    assertThat(order(p, late.orderId()).limitPrice()).as("priced ex-dividend").isEqualByComparingTo("0.45");
  }

  @Test
  void heldBracketChildrenAreAdjustedToo() {
    Player p = human("5000.00");
    String s = symbol();
    md.prevClose(s, "50.00");
    quote(s, "49.98", 1000, "50.00", 1000);
    print(s, "50.00", 100);
    var r = orders.placeGroup(p.principal(), new PlaceOrderGroupRequest(cid(), OrderGroupType.BRACKET, s, Side.BUY,
        new BigDecimal("10"), TimeInForce.GTC, new GroupLegRequest(OrderType.LIMIT, new BigDecimal("45.00"), null),
        new GroupLegRequest(null, new BigDecimal("60.00"), null),
        new GroupLegRequest(null, null, new BigDecimal("40.00")), null, "test", null, null, null), meta());
    String id = record(s, CorporateActionType.CASH_DIVIDEND, MON, null, null, "1.00", WED, null);
    at0345(MON);
    apply(id);
    var legs = orderQueries.group(p.accountId(), r.group().groupId()).orElseThrow().legs();
    OrderDto entry = legs.stream().filter(o -> o.group().role() == OrderGroupRole.ENTRY).findFirst().orElseThrow();
    OrderDto sl = legs.stream().filter(o -> o.group().role() == OrderGroupRole.STOP_LOSS).findFirst().orElseThrow();
    OrderDto tp = legs.stream().filter(o -> o.group().role() == OrderGroupRole.TAKE_PROFIT).findFirst().orElseThrow();
    assertThat(entry.limitPrice()).isEqualByComparingTo("44.00");
    assertThat(sl.stopPrice()).isEqualByComparingTo("39.00");
    assertThat(sl.group().held()).isTrue();
    assertThat(tp.limitPrice()).as("a sell limit is not reduced").isEqualByComparingTo("60.00");
  }

  private OrderDto gtc(Player p, PlaceOrderRequest req) {
    return place(p, req).order();
  }

  private static Map<String, Object> event(OrderDto o, OrderEventType type) {
    return o.events().stream().filter(e -> e.type() == type).findFirst().orElseThrow().detail();
  }
}
