package com.stocks.trading.corpactions;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupStatus;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.contract.trading.dto.GroupLegRequest;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import com.stocks.contract.trading.dto.PlaceOrderGroupRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Group orders and corporate actions (§11.9, §14): a split cancels every open leg like any open order
 * ({@code CORPORATE_ACTION}): a waiting bracket's entry and its held children, a released bracket's working take
 * profit and stop loss (their single share reservation is released) and both legs of a SELL OCO. The groups end
 * CANCELLED, or COMPLETED when a leg had executed. Invariants I1–I9 are asserted after the test by the base class.
 */
class GroupCorporateActionTest extends CorporateActionTestSupport {

  @Test
  void aSplitCancelsEveryOpenLegOfEveryGroupOnTheSymbol() {
    Player p = human("5000.00");
    String s = symbol();
    buy(p, s, "4", "100.00");                                   // shares for the SELL OCO
    quote(s, "99.98", 1000, "100.00", 1000);
    print(s, "100.00", 100);

    // a bracket waiting for its entry (held children) ...
    OrderGroupDto waiting = place(p, bracket(s, "3", OrderType.LIMIT, "90.00", "110.00", "85.00")).group();
    // ... a bracket whose market entry fills and releases its GTC children ...
    OrderGroupDto released = place(p, bracket(s, "5", OrderType.MARKET, null, "120.00", "90.00")).group();
    // ... and a SELL OCO on the shares already held
    OrderGroupDto oco = place(p, new PlaceOrderGroupRequest(cid(), OrderGroupType.OCO, s, Side.SELL,
        new BigDecimal("4"), TimeInForce.GTC, null, new GroupLegRequest(null, new BigDecimal("125.00"), null),
        new GroupLegRequest(null, null, new BigDecimal("80.00")), null, "test", null, null, null)).group();
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 1000, "100.00", 1000);
    print(s, "100.00", 100);
    step();
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 1000, "100.00", 1000);
    print(s, "100.00", 100);
    step();
    OrderGroupDto live = group(p, released.groupId());
    assertThat(leg(live, OrderGroupRole.ENTRY).status()).isEqualTo(OrderStatus.FILLED);
    assertThat(leg(live, OrderGroupRole.TAKE_PROFIT).group().held()).isFalse();
    assertThat(leg(group(p, waiting.groupId()), OrderGroupRole.TAKE_PROFIT).group().held()).isTrue();
    assertThat(accountQueries.positions(p.accountId())).singleElement().satisfies(pos -> {
      assertThat(pos.qty()).isEqualByComparingTo("9");
      assertThat(pos.availableQty()).as("4 reserved by the OCO, 5 by the bracket's pair, once each")
          .isEqualByComparingTo("0");
    });

    String id = split(s, MON, "1", "2");
    at0345(MON);
    apply(id);

    for (OrderGroupDto g : new OrderGroupDto[] {waiting, released, oco}) {
      OrderGroupDto after = group(p, g.groupId());
      assertThat(after.legs()).as(g.type().name()).allSatisfy(o -> {
        assertThat(o.reservedCash()).isEqualByComparingTo("0");
        assertThat(o.reservedQty()).isEqualByComparingTo("0");
        if (o.group().role() != OrderGroupRole.ENTRY || g != released) {
          assertThat(o.status()).isEqualTo(OrderStatus.CANCELLED);
          assertThat(o.statusReason()).isEqualTo("CORPORATE_ACTION");
        }
      });
    }
    assertThat(group(p, waiting.groupId()).status()).isEqualTo(OrderGroupStatus.CANCELLED);
    assertThat(group(p, released.groupId()).status()).as("its entry executed").isEqualTo(OrderGroupStatus.COMPLETED);
    assertThat(group(p, oco.groupId()).status()).isEqualTo(OrderGroupStatus.CANCELLED);
    assertThat(accountQueries.positions(p.accountId())).singleElement().satisfies(pos -> {
      assertThat(pos.qty()).isEqualByComparingTo("18");
      assertThat(pos.availableQty()).isEqualByComparingTo("18");
    });
    assertThat(account(p).openBuyHolds()).as("no cash held by a cancelled leg").isEqualByComparingTo("0");
  }

  // ------------------------------------------------------------------------------------------- helpers

  private com.stocks.contract.trading.dto.PlaceOrderGroupResponse place(Player p, PlaceOrderGroupRequest req) {
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

  private static PlaceOrderGroupRequest bracket(String s, String qty, OrderType entryType, String entryLimit,
                                                String tp, String sl) {
    return new PlaceOrderGroupRequest(cid(), OrderGroupType.BRACKET, s, Side.BUY, new BigDecimal(qty),
        TimeInForce.GTC, new GroupLegRequest(entryType, entryLimit == null ? null : new BigDecimal(entryLimit), null),
        new GroupLegRequest(null, new BigDecimal(tp), null), new GroupLegRequest(null, null, new BigDecimal(sl)),
        null, "test", null, null, Set.of());
  }
}
