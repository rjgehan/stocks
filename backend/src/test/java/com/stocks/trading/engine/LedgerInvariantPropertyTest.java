package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.TransferScope;
import com.stocks.common.domain.TransferType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.trading.Balances;
import com.stocks.contract.trading.CashTransferCommand;
import com.stocks.contract.trading.InvariantResult;
import com.stocks.contract.trading.OrderFilter;
import com.stocks.contract.trading.OrderStatusFilter;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.trading.order.SessionOrderMatrix;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Property test of the money invariants (§8.4 I1–I9, §26): a seeded random walk of orders (market and limit, shares
 * and notional, buys and sells), cancels, replaces, deposits and withdrawals, quote moves, prints, session edges and
 * sweeps, with the invariants and the cash-account rules checked after every step: cash never negative, buying power
 * never negative, holds never above cash, ledger = cache. Rejections are fine; an internal error never is.
 */
class LedgerInvariantPropertyTest extends TradingTestBase {

  private static final int STEPS = 150;

  @Autowired MarketCalendar calendar;

  @ParameterizedTest(name = "seed {0}")
  @ValueSource(longs = {1L, 7L, 42L, 2026L, 31337L})
  void invariantsHoldOverARandomWalk(long seed) {
    Random rnd = new Random(seed);
    List<Player> players = List.of(human(), human("1000.00"), bot());
    List<String> symbols = List.of(symbol(), symbol(), symbol());
    BigDecimal[] mids = {new BigDecimal("12.50"), new BigDecimal("101.00"), new BigDecimal("0.8500")};
    long admin = fx.admin("adm" + next()).id();
    for (int i = 0; i < symbols.size(); i++) {
      requote(symbols.get(i), mids[i], rnd);
    }
    String lastError = engine.status().lastError();
    int fills = 0;
    for (int step = 0; step < STEPS; step++) {
      Player p = players.get(rnd.nextInt(players.size()));
      int si = rnd.nextInt(symbols.size());
      String s = symbols.get(si);
      int op = rnd.nextInt(100);
      try {
        if (op < 22) {
          place(p, market(cid(), s, Side.BUY, String.valueOf(1 + rnd.nextInt(5))));
        } else if (op < 32) {
          place(p, notional(cid(), s, Side.BUY, money(1 + rnd.nextInt(150), rnd.nextInt(100))));
        } else if (op < 47) {
          BigDecimal held = heldQty(p, s);
          if (held.signum() > 0) {
            BigDecimal q = held.compareTo(BigDecimal.ONE) >= 0 && rnd.nextBoolean()
                ? BigDecimal.valueOf(1 + rnd.nextInt(held.intValue())) : held;
            place(p, market(cid(), s, Side.SELL, q.stripTrailingZeros().toPlainString()));
          }
        } else if (op < 62) {
          Side side = rnd.nextInt(3) == 0 ? Side.SELL : Side.BUY;
          BigDecimal px = mids[si].multiply(BigDecimal.valueOf(90 + rnd.nextInt(21)))
              .divide(BigDecimal.valueOf(100), tickScale(mids[si]), RoundingMode.HALF_UP);
          place(p, new PlaceOrderRequest(cid(), s, side, com.stocks.common.domain.OrderType.LIMIT,
              BigDecimal.valueOf(1 + rnd.nextInt(4)), null, px, null, null, null,
              rnd.nextBoolean() ? TimeInForce.DAY : TimeInForce.GTC, rnd.nextInt(4) == 0, "walk", null,
              Set.of("GFV_RISK")));
        } else if (op < 70) {
          List<OrderDto> open = orderQueries.list(p.accountId(),
              new OrderFilter(OrderStatusFilter.OPEN, null, null, null), null, 50).items();
          if (!open.isEmpty()) {
            orders.cancel(p.principal(), open.get(rnd.nextInt(open.size())).orderId(), meta());
          }
        } else if (op < 74) {
          CashTransferCommand c = new CashTransferCommand(TransferScope.SINGLE, p.handle(), Set.of(),
              rnd.nextBoolean() ? TransferType.DEPOSIT : TransferType.WITHDRAWAL, new BigDecimal(money(1 + rnd.nextInt(80),
              0)), null, "walk", false, true);
          transfers.apply(c, null, admin);
        } else if (op < 90) {
          mids[si] = drift(mids[si], rnd);
          time.advance(Duration.ofMillis(200 + rnd.nextInt(1500)));
          requote(s, mids[si], rnd);
          if (rnd.nextInt(3) == 0) {
            print(s, mids[si].toPlainString(), 1 + rnd.nextInt(300));
          }
          step();
        } else if (op < 97) {
          time.advance(Duration.ofSeconds(1 + rnd.nextInt(30)));
          step();
        } else {
          time.advance(Duration.ofHours(1 + rnd.nextInt(6)));     // crosses session edges and DAY expiries
          step();
          if (calendar.stateAt(time.now()).type() != SessionType.REGULAR && rnd.nextInt(4) != 0) {
            // usually skip ahead to the next open (+ a little), so the walk keeps trading over several days
            time.set(SessionOrderMatrix.nextRegularOpen(calendar, time.now()).plusSeconds(1 + rnd.nextInt(600)));
          }
          for (int i = 0; i < symbols.size(); i++) {
            requote(symbols.get(i), mids[i], rnd);
          }
          step();
        }
      } catch (ApiException e) {
        assertThat(e.code()).as("step %d op %d: %s", step, op, e.detail()).isNotEqualTo(ErrorCode.INTERNAL_ERROR);
      }
      assertMoneyRules(players, step);
    }
    for (Player p : players) {
      fills += jdbc.sql("SELECT COUNT(*) FROM execution WHERE account_id = ?").param(p.accountId())
          .query(Integer.class).single();
    }
    assertThat(fills).as("the walk actually traded").isPositive();
    String in = players.stream().map(x -> String.valueOf(x.accountId())).reduce((a, b) -> a + "," + b).orElseThrow();
    assertThat(jdbc.sql("SELECT DISTINCT side FROM execution WHERE account_id IN (" + in + ")").query(String.class)
        .list()).as("both buys and sells executed").containsExactlyInAnyOrder("BUY", "SELL");
    assertThat(engine.status().lastError()).as("no evaluation failed").isEqualTo(lastError);
    assertThat(engine.status().state()).isEqualTo(com.stocks.contract.trading.EngineState.RUNNING);
  }

  private void assertMoneyRules(List<Player> players, int step) {
    InvariantResult r = invariants.check();
    assertThat(r.failures()).as("invariants after step %d", step).isEmpty();
    for (Player p : players) {
      Balances b = accountQueries.balances(p.accountId(), time.todayEt());
      assertThat(b.cashTotal()).as("cash ≥ 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
      assertThat(b.buyingPower()).as("buying power ≥ 0").isGreaterThanOrEqualTo(BigDecimal.ZERO);
      assertThat(b.openBuyHolds()).as("holds ≤ cash").isLessThanOrEqualTo(b.cashTotal());
      assertThat(b.withdrawable()).isLessThanOrEqualTo(b.cashTotal());
      assertThat(b.settledCash().add(b.unsettledCash())).isEqualByComparingTo(b.cashTotal());
    }
  }

  private BigDecimal heldQty(Player p, String s) {
    BigDecimal reserved = orderQueries.list(p.accountId(), new OrderFilter(OrderStatusFilter.OPEN, s, null, null),
            null, 50).items().stream().filter(o -> o.side() == Side.SELL && o.status() != OrderStatus.FILLED)
        .map(OrderDto::reservedQty).reduce(BigDecimal.ZERO, BigDecimal::add);
    return accountQueries.positions(p.accountId()).stream().filter(x -> x.symbol().equals(s))
        .map(x -> x.qty().subtract(reserved)).findFirst().orElse(BigDecimal.ZERO).max(BigDecimal.ZERO);
  }

  private void requote(String s, BigDecimal mid, Random rnd) {
    int scale = tickScale(mid);
    BigDecimal tick = BigDecimal.ONE.movePointLeft(scale);
    BigDecimal half = tick.multiply(BigDecimal.valueOf(1 + rnd.nextInt(3)));
    BigDecimal bid = mid.subtract(half).setScale(scale, RoundingMode.HALF_UP);
    BigDecimal ask = mid.add(half).setScale(scale, RoundingMode.HALF_UP);
    quote(s, bid.toPlainString(), 1 + rnd.nextInt(400), ask.toPlainString(), 1 + rnd.nextInt(400));
  }

  private static BigDecimal drift(BigDecimal mid, Random rnd) {
    int scale = tickScale(mid);
    BigDecimal pct = BigDecimal.valueOf(rnd.nextInt(801) - 400).movePointLeft(4);   // ±4%
    BigDecimal next = mid.multiply(BigDecimal.ONE.add(pct)).setScale(scale, RoundingMode.HALF_UP);
    return next.compareTo(new BigDecimal("0.10")) < 0 ? new BigDecimal("0.10") : next;
  }

  private static int tickScale(BigDecimal price) {
    return price.compareTo(BigDecimal.ONE) >= 0 ? 2 : 4;
  }

  private static String money(int dollars, int cents) {
    return dollars + "." + (cents < 10 ? "0" : "") + cents;
  }
}
