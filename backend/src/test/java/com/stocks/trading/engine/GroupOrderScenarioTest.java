package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupStatus;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.trading.dto.GroupLegChangeDto;
import com.stocks.contract.trading.dto.GroupLegRequest;
import com.stocks.contract.trading.dto.GroupPreviewDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderEventDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import com.stocks.contract.trading.dto.PlaceOrderGroupRequest;
import com.stocks.contract.trading.dto.PlaceOrderGroupResponse;
import com.stocks.contract.trading.dto.ReplaceOrderGroupRequest;
import com.stocks.contract.trading.dto.ReplaceOrderRequest;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Golden engine scenarios for group orders (§11.9) on the scripted market with the engine on the calling thread:
 * BRACKET, OTOCO, OTO and OCO; children released by the entry's first execution and sized to what it bought (partial
 * fills, kept in sync); one-cancels-other on a partial fill; gaps through both legs; a SELL pair that reserves its
 * shares once; cancel and replace of a group and of a leg; expiry; a restart with held and with working children.
 * Invariants I1–I9 are asserted after each test by the base class.
 */
class GroupOrderScenarioTest extends TradingTestBase {

  // ------------------------------------------------------------------------------------------- BRACKET

  @Test
  void aBracketHoldsItsChildrenUntilTheEntryFillsThenTheTakeProfitCancelsTheStopLoss() {
    Player p = human("5000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    PlaceOrderGroupResponse r = placeGroup(p, bracket(s, "10", OrderType.LIMIT, "100.00", "110.00", "95.00"));
    OrderGroupDto g = r.group();
    assertThat(g.type()).isEqualTo(OrderGroupType.BRACKET);
    assertThat(g.status()).isEqualTo(OrderGroupStatus.ACTIVE);
    assertThat(g.legs()).extracting(o -> o.group().role()).containsExactly(OrderGroupRole.ENTRY,
        OrderGroupRole.TAKE_PROFIT, OrderGroupRole.STOP_LOSS);
    OrderDto entry = leg(g, OrderGroupRole.ENTRY);
    OrderDto tp = leg(g, OrderGroupRole.TAKE_PROFIT);
    OrderDto sl = leg(g, OrderGroupRole.STOP_LOSS);
    assertThat(entry.side()).isEqualTo(Side.BUY);
    assertThat(entry.reservedCash()).isEqualByComparingTo("1000.00");     // 10 × 100 (CAT below a cent is 0)
    assertThat(tp.side()).isEqualTo(Side.SELL);
    assertThat(tp.group().held()).isTrue();
    assertThat(tp.group().parentOrderId()).isEqualTo(entry.orderId());
    assertThat(tp.waitReason()).isEqualTo(WaitReason.AWAITING_PARENT);
    assertThat(tp.reservedQty()).isEqualByComparingTo("0");
    assertThat(sl.type()).isEqualTo(OrderType.STOP);
    assertThat(sl.stopPrice()).isEqualByComparingTo("95.00");
    assertThat(r.buyingPowerAfter()).isEqualByComparingTo(r.buyingPowerBefore().subtract(entry.reservedCash()));

    // the entry fills: both children are released for what it bought, and the TP reserves the shares once
    time.advance(Duration.ofSeconds(1));
    market(s, "99.98", "100.00");
    step();
    assertThat(order(p, entry.orderId()).status()).isEqualTo(OrderStatus.FILLED);
    OrderDto tp1 = order(p, tp.orderId());
    OrderDto sl1 = order(p, sl.orderId());
    assertThat(tp1.group().held()).isFalse();
    assertThat(sl1.group().held()).isFalse();
    assertThat(tp1.events()).extracting(OrderEventDto::type).contains(OrderEventType.ACTIVATED);
    assertThat(tp1.reservedQty().add(sl1.reservedQty())).isEqualByComparingTo("10");
    assertThat(accountQueries.positions(p.accountId())).singleElement()
        .satisfies(pos -> assertThat(pos.qty()).isEqualByComparingTo("10"));

    // the children work after their latency; the stop arms, the take profit rests
    time.advance(Duration.ofSeconds(1));
    market(s, "100.98", "101.00");
    step();
    assertThat(order(p, tp.orderId()).status()).isEqualTo(OrderStatus.WORKING);
    assertThat(order(p, sl.orderId()).waitReason()).isEqualTo(WaitReason.NOT_TRIGGERED);

    // the take profit fills: the stop loss is cancelled (OCO_FILLED)
    time.advance(Duration.ofSeconds(2));
    market(s, "110.05", "110.07");
    step();
    OrderDto tpDone = order(p, tp.orderId());
    assertThat(tpDone.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(tpDone.executions()).allSatisfy(x -> assertThat(x.price()).isGreaterThanOrEqualTo(new BigDecimal("110.00")));
    OrderDto slDone = order(p, sl.orderId());
    assertThat(slDone.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(slDone.statusReason()).isEqualTo(CancelReason.OCO_FILLED.name());
    assertThat(accountQueries.positions(p.accountId())).isEmpty();
    assertThat(group(p, g.groupId()).status()).isEqualTo(OrderGroupStatus.COMPLETED);
  }

  @Test
  void aStopLossTriggersAndFillsAndCancelsTheTakeProfit() {
    Player p = human("5000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    OrderGroupDto g = placeGroup(p, bracket(s, "5", OrderType.MARKET, null, "110.00", "95.00")).group();
    time.advance(Duration.ofSeconds(1));
    market(s, "99.98", "100.00");
    step();
    time.advance(Duration.ofSeconds(1));
    market(s, "99.98", "100.00");
    print(s, "100.00", 100);
    step();
    OrderDto sl = leg(group(p, g.groupId()), OrderGroupRole.STOP_LOSS);
    assertThat(sl.waitReason()).isEqualTo(WaitReason.NOT_TRIGGERED);

    time.advance(Duration.ofSeconds(1));
    print(s, "94.90", 100);
    step();
    assertThat(order(p, sl.orderId()).triggered()).isTrue();
    time.advance(Duration.ofSeconds(1));
    market(s, "94.80", "94.85");
    step();
    assertThat(order(p, sl.orderId()).status()).isEqualTo(OrderStatus.FILLED);
    OrderDto tp = leg(group(p, g.groupId()), OrderGroupRole.TAKE_PROFIT);
    assertThat(tp.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(tp.statusReason()).isEqualTo("OCO_FILLED");
    assertThat(accountQueries.positions(p.accountId())).isEmpty();
  }

  @Test
  void partialEntryFillsSizeTheChildrenAndACancelledEntryTrimsThem() {
    Player p = human("5000.00");
    String s = symbol();
    md.quote(s, time.now(), "99.98", 300, "100.00", 4);
    md.publish(s);
    OrderGroupDto g = placeGroup(p, bracket(s, "10", OrderType.LIMIT, "100.00", "110.00", "95.00")).group();
    OrderDto entry = leg(g, OrderGroupRole.ENTRY);
    OrderDto tp = leg(g, OrderGroupRole.TAKE_PROFIT);
    OrderDto sl = leg(g, OrderGroupRole.STOP_LOSS);

    // 4 shares at the touch; the rest rests at the limit
    time.advance(Duration.ofSeconds(1));
    md.quote(s, time.now(), "99.98", 300, "100.00", 4);
    md.publish(s);
    step();
    OrderDto e1 = order(p, entry.orderId());
    assertThat(e1.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(e1.filledQty()).isEqualByComparingTo("4");
    OrderDto tp1 = order(p, tp.orderId());
    assertThat(tp1.group().held()).isFalse();
    assertThat(tp1.qty()).as("the group quantity while the entry is open").isEqualByComparingTo("10");
    assertThat(order(p, tp.orderId()).reservedQty().add(order(p, sl.orderId()).reservedQty()))
        .as("the pair reserves the 4 bought shares once").isEqualByComparingTo("4");
    assertThat(eventDetail(tp1, OrderEventType.ACTIVATED).get("workingQty")).isEqualTo("4");

    // more of the entry fills (a trade-through print of 3 shares below the limit): the children follow
    time.advance(Duration.ofSeconds(2));
    md.quote(s, time.now(), "99.95", 300, "100.02", 300);
    md.print(s, time.now(), "99.95", 3);
    md.publish(s);
    step();
    assertThat(order(p, entry.orderId()).filledQty()).isEqualByComparingTo("7");
    assertThat(order(p, tp.orderId()).reservedQty().add(order(p, sl.orderId()).reservedQty()))
        .isEqualByComparingTo("7");
    assertThat(order(p, tp.orderId()).events()).extracting(OrderEventDto::type).contains(OrderEventType.QTY_ADJUSTED);

    // the user cancels the rest of the entry: the children are trimmed to the 7 bought shares
    orders.cancel(p.principal(), entry.orderId(), meta());
    OrderDto tp2 = order(p, tp.orderId());
    OrderDto sl2 = order(p, sl.orderId());
    assertThat(tp2.qty()).isEqualByComparingTo("7");
    assertThat(sl2.qty()).isEqualByComparingTo("7");
    assertThat(tp2.status()).isIn(OrderStatus.PENDING, OrderStatus.WORKING);
    assertThat(group(p, g.groupId()).status()).isEqualTo(OrderGroupStatus.ACTIVE);
  }

  @Test
  void aPartialFillOfTheTakeProfitCancelsTheStopLossAndTheRestKeepsWorking() {
    Player p = human("20000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    OrderGroupDto g = placeGroup(p, bracket(s, "150", OrderType.LIMIT, "100.00", "105.00", "95.00")).group();
    time.advance(Duration.ofSeconds(1));
    md.quote(s, time.now(), "99.98", 1000, "100.00", 1000);
    md.publish(s);
    step();
    assertThat(leg(group(p, g.groupId()), OrderGroupRole.ENTRY).status()).isEqualTo(OrderStatus.FILLED);
    time.advance(Duration.ofSeconds(1));
    market(s, "99.98", "100.00");
    step();
    OrderDto tp = leg(group(p, g.groupId()), OrderGroupRole.TAKE_PROFIT);
    OrderDto sl = leg(group(p, g.groupId()), OrderGroupRole.STOP_LOSS);

    time.advance(Duration.ofSeconds(2));
    md.quote(s, time.now(), "105.00", 3, "105.02", 300);    // the bid reaches the take profit: 100 of 150 fill
    md.publish(s);
    step();
    OrderDto tp1 = order(p, tp.orderId());
    assertThat(tp1.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(tp1.filledQty()).isEqualByComparingTo("100");
    OrderDto sl1 = order(p, sl.orderId());
    assertThat(sl1.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(sl1.statusReason()).isEqualTo("OCO_FILLED");
    assertThat(tp1.reservedQty()).isEqualByComparingTo("50");
    assertThat(accountQueries.positions(p.accountId())).singleElement()
        .satisfies(pos -> assertThat(pos.qty()).isEqualByComparingTo("50"));
  }

  @Test
  void aGapThroughBothLegsExecutesOnlyOneOfThem() {
    Player p = human("5000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    print(s, "100.00", 100);
    OrderGroupDto g = placeGroup(p, bracket(s, "10", OrderType.LIMIT, "100.00", "102.00", "98.00")).group();
    time.advance(Duration.ofSeconds(1));
    market(s, "99.98", "100.00");
    print(s, "100.00", 100);
    step();
    time.advance(Duration.ofSeconds(1));
    market(s, "99.98", "100.00");
    print(s, "100.00", 100);
    step();
    // in one step the price crashes through the stop and spikes through the take profit
    time.advance(Duration.ofSeconds(2));
    print(s, "97.00", 500);
    print(s, "103.00", 500);
    market(s, "103.00", "103.05");
    step();
    time.advance(Duration.ofSeconds(1));
    market(s, "96.00", "96.05");
    step();
    time.advance(Duration.ofSeconds(1));
    market(s, "96.00", "96.05");
    step();
    OrderGroupDto after = group(p, g.groupId());
    long filled = after.legs().stream().filter(o -> o.group().role() != OrderGroupRole.ENTRY)
        .filter(o -> o.filledQty().signum() > 0).count();
    assertThat(filled).as("exactly one of the two legs executes").isEqualTo(1);
    assertThat(after.legs()).filteredOn(o -> o.group().role() != OrderGroupRole.ENTRY)
        .filteredOn(o -> o.status() == OrderStatus.CANCELLED)
        .singleElement().satisfies(o -> assertThat(o.statusReason()).isEqualTo("OCO_FILLED"));
    assertThat(accountQueries.positions(p.accountId())).isEmpty();
  }

  @Test
  void bracketPriceRulesAreEnforced() {
    Player p = human("5000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    assertGroupError(p, bracket(s, "10", OrderType.LIMIT, "100.00", "99.00", "95.00"), ErrorCode.ORDER_GROUP_INVALID,
        "takeProfit.limitPrice");
    assertGroupError(p, bracket(s, "10", OrderType.LIMIT, "100.00", "110.00", "100.50"),
        ErrorCode.ORDER_GROUP_INVALID, "stopLoss.stopPrice");
    // 0.1% of 100 = 0.10: 100.05 − 99.99 = 0.06 is too close
    assertGroupError(p, bracket(s, "10", OrderType.LIMIT, "100.00", "100.05", "99.99"),
        ErrorCode.ORDER_GROUP_INVALID, "stopLoss.stopPrice");
    assertGroupError(p, bracket(s, "10.5", OrderType.LIMIT, "100.00", "110.00", "95.00"),
        ErrorCode.ORDER_GROUP_INVALID, "qty");
    assertGroupError(p, bracket(s, "10", OrderType.STOP, "100.00", "110.00", "95.00"),
        ErrorCode.ORDER_GROUP_INVALID, "entry.type");
    assertGroupError(p, bracket(s, "10", OrderType.LIMIT, "100.003", "110.00", "95.00"),
        ErrorCode.INVALID_TICK, "entry.limitPrice");
    assertGroupError(p, bracket(s, "100", OrderType.LIMIT, "100.00", "110.00", "95.00"),
        ErrorCode.INSUFFICIENT_BUYING_POWER, "qty");
    GroupPreviewDto preview = orders.previewGroup(p.principal(),
        bracket(s, "10", OrderType.LIMIT, "100.00", "100.05", "99.99"));
    assertThat(preview.valid()).isFalse();
    assertThat(preview.errors()).singleElement().satisfies(e -> {
      assertThat(e.code()).isEqualTo("ORDER_GROUP_INVALID");
      assertThat(e.field()).isEqualTo("stopLoss.stopPrice");
    });
    GroupPreviewDto ok = orders.previewGroup(p.principal(),
        bracket(s, "10", OrderType.LIMIT, "100.00", "110.00", "95.00"));
    assertThat(ok.valid()).isTrue();
    assertThat(ok.legs()).extracting(GroupPreviewDto.GroupLegPreviewDto::role).containsExactly(OrderGroupRole.ENTRY,
        OrderGroupRole.TAKE_PROFIT, OrderGroupRole.STOP_LOSS);
    assertThat(ok.buyingPowerAfter()).isEqualByComparingTo(ok.buyingPowerBefore().subtract(new BigDecimal("1000.00")));
    assertThat(orderQueries.list(p.accountId(), null, null, 50).items()).as("nothing was persisted").isEmpty();
  }

  // ------------------------------------------------------------------------------------------- OCO

  @Test
  void aSellOcoReservesTheSharesOnceAndTheFirstExecutionCancelsTheOther() {
    String s = symbol();
    Player p = holder(s, 10);
    PlaceOrderGroupResponse r = placeGroup(p, oco(s, Side.SELL, "10", "110.00", "95.00", null));
    OrderDto tp = leg(r.group(), OrderGroupRole.TAKE_PROFIT);
    OrderDto sl = leg(r.group(), OrderGroupRole.STOP_LOSS);
    assertThat(tp.group().held()).isFalse();
    assertThat(tp.reservedQty().add(sl.reservedQty())).as("the shares are reserved once").isEqualByComparingTo("10");
    // nothing left to sell
    assertThatThrownBy(() -> place(p, limit(cid(), s, Side.SELL, "1", "120.00", TimeInForce.DAY, false)))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_POSITION));

    time.advance(Duration.ofSeconds(1));
    market(s, "99.98", "100.00");
    print(s, "100.00", 100);
    step();
    time.advance(Duration.ofSeconds(1));
    print(s, "94.50", 100);                              // the stop triggers
    step();
    assertThat(order(p, sl.orderId()).triggered()).isTrue();
    time.advance(Duration.ofSeconds(1));
    market(s, "94.40", "94.45");
    step();
    assertThat(order(p, sl.orderId()).status()).isEqualTo(OrderStatus.FILLED);
    assertThat(order(p, tp.orderId()).status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(accountQueries.positions(p.accountId())).isEmpty();
  }

  @Test
  void cancellingOneOcoLegLeavesTheOtherWorkingWithTheReservation() {
    String s = symbol();
    Player p = holder(s, 10);
    OrderGroupDto g = placeGroup(p, oco(s, Side.SELL, "10", "110.00", "95.00", null)).group();
    OrderDto tp = leg(g, OrderGroupRole.TAKE_PROFIT);
    OrderDto sl = leg(g, OrderGroupRole.STOP_LOSS);
    OrderDto holder = tp.reservedQty().signum() > 0 ? tp : sl;
    OrderDto other = holder == tp ? sl : tp;
    orders.cancel(p.principal(), holder.orderId(), meta());
    assertThat(order(p, holder.orderId()).status()).isEqualTo(OrderStatus.CANCELLED);
    OrderDto left = order(p, other.orderId());
    assertThat(left.status()).isIn(OrderStatus.PENDING, OrderStatus.WORKING);
    assertThat(left.reservedQty()).isEqualByComparingTo("10");
    assertThat(group(p, g.groupId()).status()).isEqualTo(OrderGroupStatus.ACTIVE);
  }

  @Test
  void aBuyOcoHoldsTheLargerHoldOnce() {
    Player p = human("5000.00");
    String s = symbol();
    md.prevClose(s, "100.00");
    market(s, "99.98", "100.00");
    print(s, "100.00", 100);
    PlaceOrderGroupResponse r = placeGroup(p, oco(s, Side.BUY, "10", "95.00", "105.00", null));
    OrderDto tp = leg(r.group(), OrderGroupRole.TAKE_PROFIT);
    OrderDto sl = leg(r.group(), OrderGroupRole.STOP_LOSS);
    // limit 10 × 95 = 950.00 vs stop 10 × 105 × 1.02 = 1071.00: the larger, once
    assertThat(tp.reservedCash().add(sl.reservedCash())).isEqualByComparingTo("1071.00");
    assertThat(r.buyingPowerAfter()).isEqualByComparingTo(r.buyingPowerBefore().subtract(new BigDecimal("1071.00")));

    time.advance(Duration.ofSeconds(1));
    market(s, "94.90", "94.95");                          // the buy limit fills
    print(s, "95.00", 100);
    step();
    assertThat(order(p, tp.orderId()).status()).isEqualTo(OrderStatus.FILLED);
    assertThat(order(p, sl.orderId()).status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(order(p, sl.orderId()).reservedCash()).isEqualByComparingTo("0");
  }

  // ------------------------------------------------------------------------------------------- OTO / OTOCO

  @Test
  void anOtoReleasesItsSecondaryAndAnUnfilledEntryCancelsIt() {
    Player p = human("5000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    PlaceOrderGroupRequest req = new PlaceOrderGroupRequest(cid(), OrderGroupType.OTO, s, Side.BUY,
        new BigDecimal("5"), TimeInForce.GTC, new GroupLegRequest(OrderType.LIMIT, new BigDecimal("98.00"), null),
        null, null, new GroupLegRequest(OrderType.LIMIT, new BigDecimal("104.00"), null), "test", null, null,
        null);
    OrderGroupDto g = placeGroup(p, req).group();
    OrderDto entry = leg(g, OrderGroupRole.ENTRY);
    OrderDto sec = leg(g, OrderGroupRole.SECONDARY);
    assertThat(sec.group().held()).isTrue();
    assertThat(sec.timeInForce()).isEqualTo(TimeInForce.GTC);
    // the entry is cancelled before it fills: the secondary goes with it
    orders.cancel(p.principal(), entry.orderId(), meta());
    OrderDto secDone = order(p, sec.orderId());
    assertThat(secDone.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(secDone.statusReason()).isEqualTo("PARENT_NOT_FILLED");
    assertThat(group(p, g.groupId()).status()).isEqualTo(OrderGroupStatus.CANCELLED);
  }

  @Test
  void anOtocoWithAStopLimitEntryReleasesItsPairWhenTheEntryTriggersAndFills() {
    Player p = human("5000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    print(s, "100.00", 100);
    PlaceOrderGroupRequest req = new PlaceOrderGroupRequest(cid(), OrderGroupType.OTOCO, s, Side.BUY,
        new BigDecimal("3"), null, new GroupLegRequest(OrderType.STOP_LIMIT, new BigDecimal("102.50"),
        new BigDecimal("102.00")), new GroupLegRequest(null, new BigDecimal("108.00"), null),
        new GroupLegRequest(null, null, new BigDecimal("99.00")), null, "test", null, null, null);
    OrderGroupDto g = placeGroup(p, req).group();
    time.advance(Duration.ofSeconds(1));
    market(s, "99.98", "100.00");
    print(s, "100.00", 100);
    step();
    time.advance(Duration.ofSeconds(1));
    print(s, "102.10", 100);
    step();
    time.advance(Duration.ofSeconds(1));
    market(s, "102.20", "102.30");
    step();
    assertThat(leg(group(p, g.groupId()), OrderGroupRole.ENTRY).status()).isEqualTo(OrderStatus.FILLED);
    OrderGroupDto after = group(p, g.groupId());
    assertThat(leg(after, OrderGroupRole.TAKE_PROFIT).group().held()).isFalse();
    assertThat(leg(after, OrderGroupRole.STOP_LOSS).group().held()).isFalse();
  }

  // ------------------------------------------------------------------------------------------- cancel, replace

  @Test
  void cancellingTheGroupCancelsEveryOpenLegAndIsIdempotent() {
    Player p = human("5000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    OrderGroupDto g = placeGroup(p, bracket(s, "10", OrderType.LIMIT, "98.00", "110.00", "95.00")).group();
    OrderGroupDto c = orders.cancelGroup(p.principal(), g.groupId(), meta());
    assertThat(c.status()).isEqualTo(OrderGroupStatus.CANCELLED);
    assertThat(c.legs()).allSatisfy(o -> {
      assertThat(o.status()).isEqualTo(OrderStatus.CANCELLED);
      assertThat(o.statusReason()).isEqualTo("USER");
      assertThat(o.reservedCash()).isEqualByComparingTo("0");
    });
    assertThat(orders.cancelGroup(p.principal(), g.groupId(), meta()).status()).isEqualTo(OrderGroupStatus.CANCELLED);
    assertThat(account(p).buyingPower()).isEqualByComparingTo("5000.00");
  }

  @Test
  void replacingTheGroupMovesPricesAndQuantityAndKeepsTheLinks() {
    Player p = human("5000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    OrderGroupDto g = placeGroup(p, bracket(s, "10", OrderType.LIMIT, "98.00", "110.00", "95.00")).group();
    OrderDto oldEntry = leg(g, OrderGroupRole.ENTRY);
    OrderDto oldTp = leg(g, OrderGroupRole.TAKE_PROFIT);
    PlaceOrderGroupResponse r = orders.replaceGroup(p.principal(), g.groupId(), new ReplaceOrderGroupRequest(cid(),
        new BigDecimal("12"), null, new GroupLegChangeDto(new BigDecimal("97.00"), null), null,
        new GroupLegChangeDto(null, new BigDecimal("94.00")), null, null), meta());
    OrderGroupDto after = r.group();
    OrderDto entry = leg(after, OrderGroupRole.ENTRY);
    assertThat(entry.orderId()).isNotEqualTo(oldEntry.orderId());
    assertThat(entry.replacesOrderId()).isEqualTo(oldEntry.orderId());
    assertThat(entry.qty()).isEqualByComparingTo("12");
    assertThat(entry.limitPrice()).isEqualByComparingTo("97.00");
    OrderDto tp = leg(after, OrderGroupRole.TAKE_PROFIT);
    assertThat(tp.orderId()).as("an unchanged held child follows in place").isEqualTo(oldTp.orderId());
    assertThat(tp.qty()).isEqualByComparingTo("12");
    assertThat(tp.group().parentOrderId()).isEqualTo(entry.orderId());
    OrderDto sl = leg(after, OrderGroupRole.STOP_LOSS);
    assertThat(sl.stopPrice()).isEqualByComparingTo("94.00");
    assertThat(sl.group().parentOrderId()).isEqualTo(entry.orderId());
    assertThat(after.qty()).isEqualByComparingTo("12");
    assertThat(order(p, oldEntry.orderId()).status()).isEqualTo(OrderStatus.REPLACED);

    // a failing replace changes nothing (bracket rule on the merged group)
    assertThatThrownBy(() -> orders.replaceGroup(p.principal(), g.groupId(), new ReplaceOrderGroupRequest(cid(),
        null, null, null, new GroupLegChangeDto(new BigDecimal("96.00"), null), null, null, null), meta()))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.ORDER_GROUP_INVALID));
    assertThat(group(p, g.groupId()).legs()).extracting(OrderDto::orderId)
        .containsExactlyElementsOf(after.legs().stream().map(OrderDto::orderId).toList());
  }

  @Test
  void replacingALegAfterTheEntryFilledMovesTheStopAboveTheEntry() {
    Player p = human("5000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    OrderGroupDto g = placeGroup(p, bracket(s, "10", OrderType.LIMIT, "100.00", "110.00", "95.00")).group();
    time.advance(Duration.ofSeconds(1));
    market(s, "99.98", "100.00");
    step();
    time.advance(Duration.ofSeconds(1));
    market(s, "103.98", "104.00");
    print(s, "104.00", 100);
    step();
    OrderDto sl = leg(group(p, g.groupId()), OrderGroupRole.STOP_LOSS);
    // a break-even-plus stop: allowed once the entry has filled (the bracket rule only binds the entry price)
    OrderDto moved = orders.replace(p.principal(), sl.orderId(), new ReplaceOrderRequest(cid(), null, null,
        new BigDecimal("101.00"), null, null, null, null, null), meta()).order();
    assertThat(moved.stopPrice()).isEqualByComparingTo("101.00");
    assertThat(moved.group().role()).isEqualTo(OrderGroupRole.STOP_LOSS);
    assertThat(moved.group().held()).isFalse();
    OrderGroupDto after = group(p, g.groupId());
    assertThat(after.legs()).hasSize(3);
    assertThat(leg(after, OrderGroupRole.TAKE_PROFIT).reservedQty().add(moved.reservedQty()))
        .isEqualByComparingTo("10");
    // a stop above the market is still refused (it would trigger at once)
    assertThatThrownBy(() -> orders.replace(p.principal(), moved.orderId(), new ReplaceOrderRequest(cid(), null,
        null, new BigDecimal("105.00"), null, null, null, null, null), meta()))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.STOP_PRICE_WRONG_SIDE));
  }

  @Test
  void groupIdempotencyReplaysAndConflicts() {
    Player p = human("5000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    PlaceOrderGroupRequest req = bracket(s, "10", OrderType.LIMIT, "98.00", "110.00", "95.00");
    PlaceOrderGroupResponse first = placeGroup(p, req);
    PlaceOrderGroupResponse again = placeGroup(p, req);
    assertThat(again.replayed()).isTrue();
    assertThat(again.group().groupId()).isEqualTo(first.group().groupId());
    PlaceOrderGroupRequest other = new PlaceOrderGroupRequest(req.clientOrderId(), req.type(), s, req.side(),
        new BigDecimal("9"), req.timeInForce(), req.entry(), req.takeProfit(), req.stopLoss(), null, "test", null,
        null, null);
    assertThatThrownBy(() -> placeGroup(p, other)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo(ErrorCode.CLIENT_ORDER_ID_CONFLICT));
    // after a group replace, the original id still replays
    String newId = cid();
    orders.replaceGroup(p.principal(), first.group().groupId(), new ReplaceOrderGroupRequest(newId, null, null, null,
        new GroupLegChangeDto(new BigDecimal("111.00"), null), null, null, null), meta());
    assertThat(placeGroup(p, req).replayed()).isTrue();
    assertThat(group(p, first.group().groupId()).clientOrderId()).isEqualTo(newId);
  }

  // ------------------------------------------------------------------------------------------- expiry, restart

  @Test
  void aDayEntryThatExpiresUnfilledCancelsItsChildren() {
    Player p = human("5000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    OrderGroupDto g = placeGroup(p, bracket(s, "10", OrderType.LIMIT, "95.00", "110.00", "90.00")).group();
    time.set(et("2026-10-09T16:00:01"));
    step();
    OrderGroupDto after = group(p, g.groupId());
    assertThat(leg(after, OrderGroupRole.ENTRY).status()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(leg(after, OrderGroupRole.TAKE_PROFIT).status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(leg(after, OrderGroupRole.TAKE_PROFIT).statusReason()).isEqualTo("PARENT_NOT_FILLED");
    assertThat(after.status()).isEqualTo(OrderGroupStatus.EXPIRED);
  }

  @Test
  void aRestartKeepsHeldChildrenHeldAndReleasesThemWhenTheEntryFillsAfterIt() {
    Player p = human("5000.00");
    String s = symbol();
    market(s, "99.98", "100.00");
    OrderGroupDto g = placeGroup(p, bracket(s, "10", OrderType.LIMIT, "99.00", "110.00", "95.00")).group();
    time.advance(Duration.ofSeconds(1));
    market(s, "99.98", "100.00");
    step();
    engine.restart();
    OrderDto tp = leg(group(p, g.groupId()), OrderGroupRole.TAKE_PROFIT);
    assertThat(tp.group().held()).isTrue();
    assertThat(engine.status().state().name()).isEqualTo("RUNNING");

    time.advance(Duration.ofSeconds(1));
    market(s, "98.95", "98.99");                         // the entry fills after the restart
    step();
    OrderGroupDto after = group(p, g.groupId());
    assertThat(leg(after, OrderGroupRole.ENTRY).status()).isEqualTo(OrderStatus.FILLED);
    assertThat(leg(after, OrderGroupRole.TAKE_PROFIT).group().held()).isFalse();

    // and a restart with working children continues: the stop triggers on live prints after it
    engine.restart();
    time.advance(Duration.ofSeconds(1));
    market(s, "98.95", "98.99");
    print(s, "99.00", 100);
    step();
    time.advance(Duration.ofSeconds(1));
    print(s, "94.00", 100);
    step();
    time.advance(Duration.ofSeconds(1));
    market(s, "93.90", "93.95");
    step();
    OrderGroupDto done = group(p, g.groupId());
    assertThat(leg(done, OrderGroupRole.STOP_LOSS).status()).isEqualTo(OrderStatus.FILLED);
    assertThat(leg(done, OrderGroupRole.TAKE_PROFIT).status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(done.status()).isEqualTo(OrderGroupStatus.COMPLETED);
  }

  // ------------------------------------------------------------------------------------------- helpers

  private void market(String s, String bid, String ask) {
    quote(s, bid, 1000, ask, 1000);
  }

  private PlaceOrderGroupResponse placeGroup(Player p, PlaceOrderGroupRequest req) {
    return orders.placeGroup(p.principal(), req, meta());
  }

  private OrderGroupDto group(Player p, String groupId) {
    OrderGroupDto g = orderQueries.group(p.accountId(), groupId).orElseThrow();
    return new OrderGroupDto(g.groupId(), g.clientOrderId(), g.type(), g.symbol(), g.qty(), g.timeInForce(),
        g.status(), g.createdAt(), g.updatedAt(), g.legs().stream().map(o -> order(p, o.orderId())).toList());
  }

  private static OrderDto leg(OrderGroupDto g, OrderGroupRole role) {
    return g.legs().stream().filter(o -> o.group().role() == role).findFirst().orElseThrow();
  }

  static PlaceOrderGroupRequest bracket(String s, String qty, OrderType entryType, String entryLimit, String tp,
                                        String sl) {
    return new PlaceOrderGroupRequest(cid(), OrderGroupType.BRACKET, s, Side.BUY, new BigDecimal(qty), null,
        new GroupLegRequest(entryType, entryLimit == null ? null : new BigDecimal(entryLimit), null),
        new GroupLegRequest(null, new BigDecimal(tp), null), new GroupLegRequest(null, null, new BigDecimal(sl)),
        null, "test", null, null, null);
  }

  static PlaceOrderGroupRequest oco(String s, Side side, String qty, String limit, String stop, String stopLimit) {
    return new PlaceOrderGroupRequest(cid(), OrderGroupType.OCO, s, side, new BigDecimal(qty), TimeInForce.GTC, null,
        new GroupLegRequest(null, new BigDecimal(limit), null),
        new GroupLegRequest(null, stopLimit == null ? null : new BigDecimal(stopLimit), new BigDecimal(stop)), null,
        "test", null, null, Set.of());
  }

  private void assertGroupError(Player p, PlaceOrderGroupRequest req, ErrorCode code, String field) {
    assertThatThrownBy(() -> placeGroup(p, req)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo(code);
      assertThat(e.fields()).extracting(com.stocks.common.error.ProblemField::field).contains(field);
    });
  }

  private static java.util.Map<String, Object> eventDetail(OrderDto o, OrderEventType type) {
    return o.events().stream().filter(e -> e.type() == type).findFirst().orElseThrow().detail();
  }

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
}
