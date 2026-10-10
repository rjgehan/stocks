package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.trading.Balances;
import com.stocks.contract.trading.InvariantResult;
import com.stocks.contract.trading.OrderFilter;
import com.stocks.contract.trading.OrderStatusFilter;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.ReplaceOrderRequest;
import com.stocks.trading.order.SessionOrderMatrix;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Property test of stops (§8.4 I1–I9, §10.3, §12.7, §26): a seeded random walk of STOP, STOP_LIMIT and TRAILING_STOP
 * buys and sells mixed with market buys, cancels, replaces, quote and print moves (that trigger them), session edges
 * and restarts. After every step: the invariants hold, no hold is ever negative, holds never exceed cash, buying
 * power is never negative, an armed stop never has a fill, and every triggered stop fills only after its trigger.
 * Rejections are fine; an internal error never is.
 */
class StopInvariantPropertyTest extends TradingTestBase {

  private static final int STEPS = 150;

  @Autowired MarketCalendar calendar;

  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {3L, 11L, 99L, 2027L, 4242L})
  void stopsKeepTheMoneyInvariantsOverARandomWalk(long seed) {
    Random rnd = new Random(seed);
    List<Player> players = List.of(human("2000.00"), human(), bot());
    List<String> symbols = List.of(symbol(), symbol());
    BigDecimal[] mids = {new BigDecimal("40.00"), new BigDecimal("0.9000")};
    for (int i = 0; i < symbols.size(); i++) {
      requote(symbols.get(i), mids[i], rnd, true);
    }
    String lastError = engine.status().lastError();
    for (int step = 0; step < STEPS; step++) {
      Player p = players.get(rnd.nextInt(players.size()));
      int si = rnd.nextInt(symbols.size());
      String s = symbols.get(si);
      BigDecimal mid = mids[si];
      int op = rnd.nextInt(100);
      try {
        if (op < 14) {
          place(p, market(cid(), s, Side.BUY, String.valueOf(1 + rnd.nextInt(6))));
        } else if (op < 40) {
          placeStop(p, s, mid, heldQty(p, s), rnd);
        } else if (op < 48) {
          List<OrderDto> open = open(p);
          if (!open.isEmpty()) {
            orders.cancel(p.principal(), open.get(rnd.nextInt(open.size())).orderId(), meta());
          }
        } else if (op < 54) {
          List<OrderDto> open = open(p).stream().filter(o -> o.type() != OrderType.MARKET).toList();
          if (!open.isEmpty()) {
            OrderDto o = open.get(rnd.nextInt(open.size()));
            orders.replace(p.principal(), o.orderId(), replacement(o, mid, rnd), meta());
          }
        } else if (op < 86) {
          mids[si] = drift(mid, rnd);
          time.advance(Duration.ofMillis(300 + rnd.nextInt(2500)));
          requote(s, mids[si], rnd, rnd.nextInt(4) != 0);
          step();
        } else if (op < 94) {
          time.advance(Duration.ofSeconds(1 + rnd.nextInt(25)));
          step();
        } else if (op < 97) {
          engine.restart();
          step();
        } else {
          time.advance(Duration.ofHours(1 + rnd.nextInt(6)));
          step();
          if (calendar.stateAt(time.now()).type() != SessionType.REGULAR && rnd.nextInt(3) != 0) {
            time.set(SessionOrderMatrix.nextRegularOpen(calendar, time.now()).plusSeconds(1 + rnd.nextInt(300)));
          }
          for (int i = 0; i < symbols.size(); i++) {
            requote(symbols.get(i), mids[i], rnd, true);
          }
          step();
        }
      } catch (ApiException e) {
        assertThat(e.code()).as("step %d op %d: %s", step, op, e.detail()).isNotEqualTo(ErrorCode.INTERNAL_ERROR);
      }
      assertRules(players, step);
    }
    String in = accounts(players);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_order WHERE triggered = TRUE AND account_id IN (" + in + ")")
        .query(Integer.class).single()).as("the walk triggered stops").isPositive();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM execution e JOIN trade_order o ON o.id = e.order_id WHERE o.order_type"
            + " IN ('STOP','STOP_LIMIT','TRAILING_STOP') AND o.account_id IN (" + in + ")").query(Integer.class)
        .single()).as("and filled some").isPositive();
    assertThat(engine.status().lastError()).as("no evaluation failed").isEqualTo(lastError);
    assertThat(engine.status().state()).isEqualTo(com.stocks.contract.trading.EngineState.RUNNING);
  }

  private void placeStop(Player p, String s, BigDecimal mid, BigDecimal held, Random rnd) {
    boolean sell = held.compareTo(BigDecimal.ONE) >= 0 && rnd.nextBoolean();
    Side side = sell ? Side.SELL : Side.BUY;
    int qty = sell ? 1 + rnd.nextInt(held.intValue()) : 1 + rnd.nextInt(5);
    int scale = tickScale(mid);
    int kind = rnd.nextInt(3);
    BigDecimal offset = BigDecimal.valueOf(1 + rnd.nextInt(6)).movePointLeft(2);      // 1–6% away
    BigDecimal stop = (sell ? mid.multiply(BigDecimal.ONE.subtract(offset)) : mid.multiply(BigDecimal.ONE.add(offset)))
        .setScale(scale, sell ? RoundingMode.DOWN : RoundingMode.UP);
    PlaceOrderRequest req;
    if (kind == 0) {
      req = new PlaceOrderRequest(cid(), s, side, OrderType.STOP, BigDecimal.valueOf(qty), null, null, stop, null,
          null, rnd.nextBoolean() ? TimeInForce.DAY : TimeInForce.GTC, false, "walk", null, Set.of("GFV_RISK"));
    } else if (kind == 1) {
      BigDecimal band = mid.multiply(BigDecimal.valueOf(rnd.nextInt(3)).movePointLeft(2)).setScale(scale,
          RoundingMode.HALF_UP);
      BigDecimal limit = sell ? stop.subtract(band) : stop.add(band);
      req = new PlaceOrderRequest(cid(), s, side, OrderType.STOP_LIMIT, BigDecimal.valueOf(qty), null, limit, stop,
          null, null, rnd.nextBoolean() ? TimeInForce.DAY : TimeInForce.GTC, false, "walk", null, Set.of("GFV_RISK"));
    } else if (rnd.nextBoolean()) {
      req = new PlaceOrderRequest(cid(), s, side, OrderType.TRAILING_STOP, BigDecimal.valueOf(qty), null, null, null,
          null, BigDecimal.valueOf(1 + rnd.nextInt(8)), TimeInForce.DAY, false, "walk", null, Set.of("GFV_RISK"));
    } else {
      BigDecimal amount = mid.multiply(offset).setScale(scale, RoundingMode.UP);
      req = new PlaceOrderRequest(cid(), s, side, OrderType.TRAILING_STOP, BigDecimal.valueOf(qty), null, null, null,
          amount, null, TimeInForce.DAY, false, "walk", null, Set.of("GFV_RISK"));
    }
    place(p, req);
  }

  private static ReplaceOrderRequest replacement(OrderDto o, BigDecimal mid, Random rnd) {
    int scale = tickScale(mid);
    BigDecimal nudge = BigDecimal.ONE.movePointLeft(scale).multiply(BigDecimal.valueOf(1 + rnd.nextInt(20)));
    boolean buy = o.side() == Side.BUY;
    BigDecimal stop = o.stopPrice() == null || o.type() == OrderType.TRAILING_STOP ? null
        : (buy ? o.stopPrice().add(nudge) : o.stopPrice().subtract(nudge).max(BigDecimal.ONE.movePointLeft(scale)));
    BigDecimal limit = o.limitPrice() == null ? null : (buy ? o.limitPrice().add(nudge) : o.limitPrice());
    BigDecimal pct = o.type() == OrderType.TRAILING_STOP ? BigDecimal.valueOf(1 + rnd.nextInt(8)) : null;
    BigDecimal qty = rnd.nextBoolean() ? o.qty() : o.qty().add(BigDecimal.ONE);
    return new ReplaceOrderRequest(cid(), qty, limit, stop, null, pct, null, null, null);
  }

  private void assertRules(List<Player> players, int step) {
    InvariantResult r = invariants.check();
    assertThat(r.failures()).as("invariants after step %d", step).isEmpty();
    String in = accounts(players);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_order WHERE (reserved_cash < 0 OR reserved_qty < 0)"
            + " AND account_id IN (" + in + ")").query(Integer.class).single()).as("holds never negative").isZero();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM execution e JOIN trade_order o ON o.id = e.order_id"
            + " WHERE o.order_type IN ('STOP','STOP_LIMIT','TRAILING_STOP') AND o.account_id IN (" + in + ")"
            + " AND (o.triggered = FALSE OR e.executed_at < o.triggered_at)").query(Integer.class).single())
        .as("a stop fills only after it triggered (step %d)", step).isZero();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_order o WHERE o.triggered = TRUE AND o.account_id IN (" + in + ")"
            + " AND NOT EXISTS (SELECT 1 FROM order_event v WHERE v.order_id = o.id AND v.event_type = 'TRIGGERED')")
        .query(Integer.class).single()).as("every trigger is on the timeline").isZero();
    for (Player p : players) {
      Balances b = accountQueries.balances(p.accountId(), time.todayEt());
      assertThat(b.cashTotal()).as("cash ≥ 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
      assertThat(b.buyingPower()).as("buying power ≥ 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
      assertThat(b.openBuyHolds()).as("holds ≤ cash").isLessThanOrEqualTo(b.cashTotal());
    }
  }

  private List<OrderDto> open(Player p) {
    return orderQueries.list(p.accountId(), new OrderFilter(OrderStatusFilter.OPEN, null, null, null), null, 50)
        .items();
  }

  private BigDecimal heldQty(Player p, String s) {
    BigDecimal reserved = orderQueries.list(p.accountId(), new OrderFilter(OrderStatusFilter.OPEN, s, null, null),
            null, 50).items().stream().filter(o -> o.side() == Side.SELL).map(OrderDto::reservedQty)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return accountQueries.positions(p.accountId()).stream().filter(x -> x.symbol().equals(s))
        .map(x -> x.qty().subtract(reserved)).findFirst().orElse(BigDecimal.ZERO).max(BigDecimal.ZERO)
        .setScale(0, RoundingMode.FLOOR);
  }

  private static String accounts(List<Player> players) {
    return players.stream().map(x -> String.valueOf(x.accountId())).reduce((a, b) -> a + "," + b).orElseThrow();
  }

  /** A fresh two-sided quote around {@code mid}, and usually a consolidated print near it. */
  private void requote(String s, BigDecimal mid, Random rnd, boolean withPrint) {
    int scale = tickScale(mid);
    BigDecimal tick = BigDecimal.ONE.movePointLeft(scale);
    BigDecimal half = tick.multiply(BigDecimal.valueOf(1 + rnd.nextInt(3)));
    BigDecimal bid = mid.subtract(half).setScale(scale, RoundingMode.HALF_UP);
    BigDecimal ask = mid.add(half).setScale(scale, RoundingMode.HALF_UP);
    quote(s, bid.toPlainString(), 1 + rnd.nextInt(400), ask.toPlainString(), 1 + rnd.nextInt(400));
    if (withPrint) {
      print(s, mid.setScale(scale, RoundingMode.HALF_UP).toPlainString(), 1 + rnd.nextInt(300));
    }
  }

  private static BigDecimal drift(BigDecimal mid, Random rnd) {
    int scale = tickScale(mid);
    BigDecimal pct = BigDecimal.valueOf(rnd.nextInt(1201) - 600).movePointLeft(4);   // ±6%
    BigDecimal next = mid.multiply(BigDecimal.ONE.add(pct)).setScale(scale, RoundingMode.HALF_UP);
    return next.compareTo(new BigDecimal("0.10")) < 0 ? new BigDecimal("0.10") : next;
  }

  private static int tickScale(BigDecimal price) {
    return price.compareTo(BigDecimal.ONE) >= 0 ? 2 : 4;
  }
}
