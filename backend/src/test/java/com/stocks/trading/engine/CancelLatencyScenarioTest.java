package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderEventDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import com.stocks.contract.trading.dto.ReplaceOrderRequest;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Cancel latency (§11.10, on here; off by default): a user cancel of a working order shows {@code PENDING_CANCEL}
 * for 150 ms + 0–100 ms of seeded jitter and can still fill meanwhile (a full fill wins); then the sweep cancels it.
 * A PENDING order, an admin cancel and a held group child cancelled alone cancel at once (a group cancel leaves
 * the children of a pending entry held until it ends); a pending cancel cannot be replaced, and
 * cancelling it again changes nothing. The instants are stored, so a restart keeps the pending cancel.
 */
@TestPropertySource(properties = "stocks.rules.cancel-latency.enabled=true")
class CancelLatencyScenarioTest extends TradingTestBase {

  @Test
  void aUserCancelOfAWorkingOrderIsPendingThenTakesEffect() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 500, "100.00", 500);
    OrderDto o = place(p, limit(cid(), s, Side.BUY, "2", "95.00", TimeInForce.DAY, false)).order();
    after(Duration.ofSeconds(1));
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.WORKING);

    Instant requested = time.now();
    OrderDto pending = orders.cancel(p.principal(), o.orderId(), meta());
    assertThat(pending.status()).isEqualTo(OrderStatus.PENDING_CANCEL);
    assertThat(pending.cancelRequestedAt()).isEqualTo(requested);
    assertThat(pending.reservedCash()).as("still holds until the cancel takes effect").isEqualByComparingTo("190.00");
    OrderEventDto ev = order(p, o.orderId()).events().stream()
        .filter(e -> e.type() == OrderEventType.CANCEL_REQUESTED).findFirst().orElseThrow();
    Instant effective = Instant.parse(String.valueOf(ev.detail().get("effectiveAt")));
    assertThat(effective).isBetween(requested.plusMillis(150), requested.plusMillis(250));
    assertThat(orderQueries.list(p.accountId(), null, null, 10).items()).first()
        .satisfies(x -> assertThat(x.status()).isEqualTo(OrderStatus.PENDING_CANCEL));

    // cancelling again changes nothing; a replace is refused
    assertThat(orders.cancel(p.principal(), o.orderId(), meta()).status()).isEqualTo(OrderStatus.PENDING_CANCEL);
    assertThatThrownBy(() -> orders.replace(p.principal(), o.orderId(), new ReplaceOrderRequest(cid(), null,
        new BigDecimal("94.00"), null, null, null, null, null, null), meta()))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.ORDER_NOT_REPLACEABLE));

    // a restart keeps it pending; the sweep cancels it once effective
    engine.restart();
    time.set(effective.minusMillis(1));
    step();
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.PENDING_CANCEL);
    time.set(effective);
    step();
    OrderDto done = order(p, o.orderId());
    assertThat(done.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(done.statusReason()).isEqualTo("USER");
    assertThat(done.reservedCash()).isEqualByComparingTo("0");
    assertThat(account(p).buyingPower()).isEqualByComparingTo("500.00");
  }

  @Test
  void aFillDuringTheLatencyWins() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 500, "100.00", 500);
    OrderDto o = place(p, limit(cid(), s, Side.BUY, "2", "95.00", TimeInForce.DAY, false)).order();
    after(Duration.ofSeconds(1));                       // working (and resting) from here
    time.advance(Duration.ofMillis(900));
    orders.cancel(p.principal(), o.orderId(), meta());  // takes effect 150–250 ms later
    time.advance(Duration.ofMillis(100));
    quote(s, "94.90", 500, "94.95", 500);               // the limit is crossed before the cancel takes effect
    step();
    OrderDto filled = order(p, o.orderId());
    assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(filled.cancelRequestedAt()).isNotNull();
    time.advance(Duration.ofSeconds(1));
    step();
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.FILLED);
  }

  @Test
  void pendingOrdersAdminCancelsAndHeldChildrenCancelAtOnce() {
    Player p = human("5000.00");
    String s = symbol();
    quote(s, "99.98", 500, "100.00", 500);
    OrderDto queued = place(p, limit(cid(), s, Side.BUY, "1", "95.00", TimeInForce.DAY, false)).order();
    assertThat(orders.cancel(p.principal(), queued.orderId(), meta()).status()).as("PENDING: immediate")
        .isEqualTo(OrderStatus.CANCELLED);

    OrderDto working = place(p, limit(cid(), s, Side.BUY, "1", "95.00", TimeInForce.DAY, false)).order();
    after(Duration.ofSeconds(1));
    OrderDto adminCancelled = engine.run(new EngineCommand.CancelOrderCmd(null, working.orderId(),
        com.stocks.common.domain.CancelReason.ADMIN, 0));
    assertThat(adminCancelled.status()).isEqualTo(OrderStatus.CANCELLED);

    // a held child cancelled on its own goes at once
    OrderGroupDto alone = orders.placeGroup(p.principal(), GroupOrderScenarioTest.bracket(s, "1", OrderType.LIMIT,
        "95.00", "110.00", "90.00"), meta()).group();
    after(Duration.ofSeconds(1));
    OrderDto tp = alone.legs().stream().filter(l -> l.group().role() == OrderGroupRole.TAKE_PROFIT).findFirst()
        .orElseThrow();
    assertThat(orders.cancel(p.principal(), tp.orderId(), meta()).status()).isEqualTo(OrderStatus.CANCELLED);

    // a group cancel: the working entry is pending; its held children wait for it and go with it unfilled
    OrderGroupDto g = orders.placeGroup(p.principal(), GroupOrderScenarioTest.bracket(s, "2", OrderType.LIMIT,
        "95.00", "110.00", "90.00"), meta()).group();
    after(Duration.ofSeconds(1));
    OrderGroupDto cancelled = orders.cancelGroup(p.principal(), g.groupId(), meta());
    assertThat(cancelled.legs()).filteredOn(l -> l.group().role() != OrderGroupRole.ENTRY)
        .allSatisfy(l -> {
          assertThat(l.status()).isIn(OrderStatus.PENDING, OrderStatus.WORKING);
          assertThat(l.group().held()).isTrue();
        });
    OrderDto entry = cancelled.legs().stream().filter(l -> l.group().role() == OrderGroupRole.ENTRY).findFirst()
        .orElseThrow();
    assertThat(entry.status()).isEqualTo(OrderStatus.PENDING_CANCEL);
    after(Duration.ofMillis(300));
    assertThat(order(p, entry.orderId()).status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(cancelled.legs()).filteredOn(l -> l.group().role() != OrderGroupRole.ENTRY)
        .allSatisfy(l -> {
          OrderDto now = order(p, l.orderId());
          assertThat(now.status()).isEqualTo(OrderStatus.CANCELLED);
          assertThat(now.statusReason()).isEqualTo("PARENT_NOT_FILLED");
        });
  }

  /**
   * Review finding: a group cancel (or cancel-all) used to cancel the held take profit and stop loss at once while
   * the entry's cancel was only requested, so an entry that filled during the latency left the shares unprotected.
   * Now both cancel paths behave like {@code DELETE /orders/{entry}}: the entry's fill releases its children.
   */
  @Test
  void anEntryThatFillsDuringAGroupCancelKeepsItsBracket() {
    for (boolean viaCancelAll : new boolean[] {false, true}) {
      Player p = human("5000.00");
      String s = symbol();
      quote(s, "99.98", 1000, "100.02", 1000);
      OrderGroupDto g = orders.placeGroup(p.principal(),
          GroupOrderScenarioTest.bracket(s, "10", OrderType.LIMIT, "99.00", "110.00", "95.00"), meta()).group();
      after(Duration.ofSeconds(1));
      OrderDto entry = g.legs().stream().filter(o -> o.group().role() == OrderGroupRole.ENTRY).findFirst()
          .orElseThrow();
      assertThat(order(p, entry.orderId()).status()).isEqualTo(OrderStatus.WORKING);

      if (viaCancelAll) {
        orders.cancelAll(p.principal(), s, meta());
      } else {
        orders.cancelGroup(p.principal(), g.groupId(), meta());
      }
      time.advance(Duration.ofMillis(100));
      quote(s, "98.90", 1000, "98.95", 1000);
      step();
      after(Duration.ofSeconds(1));

      assertThat(order(p, entry.orderId()).status()).as("the entry filled during its cancel latency")
          .isEqualTo(OrderStatus.FILLED);
      assertThat(g.legs()).filteredOn(l -> l.group().role() != OrderGroupRole.ENTRY).hasSize(2)
          .allSatisfy(l -> {
            OrderDto now = order(p, l.orderId());
            assertThat(now.status()).as("%s protects the 10 shares (cancel-all %s)", now.group().role(),
                viaCancelAll).isIn(OrderStatus.PENDING, OrderStatus.WORKING);
            assertThat(now.group().held()).isFalse();
          });
    }
  }
}
