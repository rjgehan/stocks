package com.stocks.trading.engine;

import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.LedgerEntryType;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.events.ExecutionCreated;
import com.stocks.common.events.OrderChanged;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.money.Money;
import com.stocks.common.money.Qty;
import com.stocks.common.money.Rounding;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.trading.Balances;
import com.stocks.trading.account.BalanceCalculator;
import com.stocks.trading.account.GfvService;
import com.stocks.trading.config.RulesProperties;
import com.stocks.trading.engine.FillModel.Leg;
import com.stocks.trading.engine.write.ExecutionWriter;
import com.stocks.trading.engine.write.LedgerWriter;
import com.stocks.trading.engine.write.OrderWriter;
import com.stocks.trading.engine.write.PositionWriter;
import com.stocks.trading.fees.FeeCalculator;
import com.stocks.trading.fees.Fees;
import com.stocks.trading.order.HoldCalculator;
import com.stocks.trading.order.OrderStateMachine;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.LotRow;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import com.stocks.trading.store.PositionRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Posts fills inside the engine transaction (§12.8): fees, the BUY execution-time guard, the {@code execution} row,
 * its {@code cash_ledger} row (net of fees), FIFO lots and the position, realized P&L, GFV detection, the order's new
 * state and its timeline event; then, after commit, {@code ExecutionCreated}, {@code OrderChanged} and
 * {@code AccountChanged}.
 */
@Component
public class PostingService {

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** What happened to the order. */
  public record Outcome(OrderStatus status, BigDecimal filledQty, int executions, boolean cancelledRemainder) {}

  private final OrderStore orders;
  private final AccountStore accounts;
  private final OrderWriter orderWriter;
  private final ExecutionWriter executionWriter;
  private final LedgerWriter ledger;
  private final PositionWriter positions;
  private final FeeCalculator fees;
  private final HoldCalculator holds;
  private final GfvService gfv;
  private final TradingClock clock;
  private final PublicId publicIds;
  private final RulesProperties rules;
  private final JdbcClient jdbc;
  private final JsonMapper json;

  public PostingService(OrderStore orders, AccountStore accounts, OrderWriter orderWriter,
                        ExecutionWriter executionWriter, LedgerWriter ledger, PositionWriter positions,
                        FeeCalculator fees, HoldCalculator holds, GfvService gfv, TradingClock clock,
                        PublicId publicIds, RulesProperties rules, JdbcClient jdbc, JsonMapper json) {
    this.orders = orders;
    this.accounts = accounts;
    this.orderWriter = orderWriter;
    this.executionWriter = executionWriter;
    this.ledger = ledger;
    this.positions = positions;
    this.fees = fees;
    this.holds = holds;
    this.gfv = gfv;
    this.clock = clock;
    this.publicIds = publicIds;
    this.rules = rules;
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Posts {@code legs} for {@code order} at {@code now}, in order. A BUY leg the account can no longer afford is
   * shrunk to the largest affordable quantity and the remainder is cancelled with
   * {@code INSUFFICIENT_BUYING_POWER_AT_EXECUTION} (§10.3); cash never goes negative.
   */
  public Outcome post(OrderRow order, List<Leg> legs, ExecQuote quote, Instant now, EngineTx tx) {
    EngineThread.assertCurrent();
    LocalDate tradeDate = clock.tradeDate(now);
    LocalDate settleDate = clock.settlementDate(tradeDate);
    BigDecimal reservedCash = order.reservedCash();
    BigDecimal reservedQty = order.reservedQty();
    BigDecimal filled = order.filledQty();
    BigDecimal filledPrincipal = order.filledPrincipal();
    BigDecimal feesTotal = order.feesTotal();
    OrderStatus status = order.status();
    int posted = 0;
    boolean cancelRemainder = false;

    for (Leg leg : legs) {
      BigDecimal qty = leg.qty();
      BigDecimal price = leg.price();
      BigDecimal[] sums = orders.sameDaySums(order.id(), tradeDate);
      BigDecimal principal = Rounding.principal(order.side(), qty, price);
      Fees f = fees.forExecution(order.side(), tradeDate, sums[0], sums[1], qty, principal);

      if (order.isBuy()) {
        BigDecimal allowed = reservedCash.add(freeBuyingPower(order.accountId(), now));
        if (principal.add(f.total()).compareTo(allowed) > 0) {
          qty = affordable(order, price, allowed, tradeDate, sums);
          cancelRemainder = true;
          if (qty.signum() <= 0) {
            break;
          }
          principal = Rounding.principal(order.side(), qty, price);
          f = fees.forExecution(order.side(), tradeDate, sums[0], sums[1], qty, principal);
        }
      } else {
        BigDecimal held = accounts.position(order.accountId(), order.symbol()).map(PositionRow::qty)
            .orElse(Qty.ZERO);
        if (qty.compareTo(held) > 0) {
          qty = held;
          cancelRemainder = true;
          if (qty.signum() <= 0) {
            break;
          }
          principal = Rounding.principal(order.side(), qty, price);
          f = fees.forExecution(order.side(), tradeDate, sums[0], sums[1], qty, principal);
        }
      }

      BigDecimal net = order.isBuy() ? principal.add(f.total()).negate() : principal.subtract(f.total());
      String exeId = publicIds.next(Prefix.EXECUTION);
      long executionId;
      if (order.isBuy()) {
        BigDecimal cost = principal.add(f.total());
        GfvService.Funding funding = gfv.attributeFunding(order.accountId(), cost,
            com.stocks.common.time.Et.date(now));
        executionId = executionWriter.insert(new ExecutionWriter.NewExecution(exeId, order.id(), order.accountId(),
            order.symbol(), Side.BUY, qty, price, principal, f, net, null, leg.liquidity(), now, tradeDate,
            settleDate, quote));
        ledger.append(order.accountId(), LedgerEntryType.TRADE_BUY, net, tradeDate, settleDate, now, null,
            executionId, order.symbol(), null, "EXEC:" + exeId);
        positions.openLot(order.accountId(), order.symbol(), executionId, tradeDate, now, qty, cost,
            funding.unsettledFunding(), funding.fundingSettleDate());
        positions.add(order.accountId(), order.symbol(), qty, cost, now);
      } else {
        Relief relief = relieve(order.accountId(), order.symbol(), qty, tradeDate, now);
        BigDecimal realized = net.subtract(relief.cost());
        executionId = executionWriter.insert(new ExecutionWriter.NewExecution(exeId, order.id(), order.accountId(),
            order.symbol(), Side.SELL, qty, price, principal, f, net, realized, leg.liquidity(), now, tradeDate,
            settleDate, quote));
        ledger.append(order.accountId(), LedgerEntryType.TRADE_SELL, net, tradeDate, settleDate, now, null,
            executionId, order.symbol(), null, "EXEC:" + exeId);
        if (relief.violating() != null) {
          gfv.recordSell(order.accountId(), order.playerId(), order.id(), order.publicId(), executionId,
              order.symbol(), relief.violating(), tradeDate, now, tx);
        }
      }
      posted++;

      filled = filled.add(qty);
      filledPrincipal = filledPrincipal.add(principal);
      feesTotal = feesTotal.add(f.total());
      BigDecimal remaining = order.qty() == null ? Qty.ZERO : order.qty().subtract(filled);
      OrderStatus next = remaining.signum() <= 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
      if (status == OrderStatus.PENDING) {
        status = OrderStatus.WORKING;   // activation and fill in one step: WORKING was written first (§11.5)
      }
      status = OrderStateMachine.transition(status, next);
      if (order.isBuy()) {
        BigDecimal cost = principal.add(f.total());
        reservedCash = status == OrderStatus.FILLED ? Money.ZERO
            : reservedCash.subtract(reservedCash.min(cost)).min(remainingHold(order, remaining, price, tradeDate));
      } else {
        reservedQty = status == OrderStatus.FILLED ? Qty.ZERO : reservedQty.subtract(qty).max(Qty.ZERO);
      }
      BigDecimal avg = averagePrice(order.id());
      orderWriter.applyFill(order.id(), filled, filledPrincipal, avg, feesTotal, status, reservedCash, reservedQty,
          now, status == OrderStatus.FILLED ? now : null, status == OrderStatus.FILLED ? null : order.waitReason());
      OrderEventType type = status == OrderStatus.FILLED ? OrderEventType.FILL : OrderEventType.PARTIAL_FILL;
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("executionId", exeId);
      detail.put("qty", qty);
      detail.put("price", price);
      detail.put("liquidity", leg.liquidity().name());
      detail.put("quality", quote.quality().name());
      orderWriter.event(order.id(), type, status, now, json.writeValueAsString(detail));
      tx.publish(new ExecutionCreated(order.accountId(), order.playerId(), exeId, order.publicId(), order.symbol(),
          order.side(), qty, price, net, now));
      tx.publish(new OrderChanged(order.accountId(), order.playerId(), order.publicId(), type, status, now));
      if (status == OrderStatus.FILLED || cancelRemainder) {
        break;
      }
    }

    if (cancelRemainder && status != OrderStatus.FILLED) {
      status = OrderStateMachine.transition(status == OrderStatus.PENDING ? OrderStatus.WORKING : status,
          OrderStatus.CANCELLED);
      orderWriter.finish(order.id(), OrderStatus.CANCELLED, CancelReason.INSUFFICIENT_BUYING_POWER_AT_EXECUTION.name(),
          now);
      orderWriter.event(order.id(), OrderEventType.CANCELLED, OrderStatus.CANCELLED, now,
          json.writeValueAsString(Map.of("reason", CancelReason.INSUFFICIENT_BUYING_POWER_AT_EXECUTION.name())));
      tx.publish(new OrderChanged(order.accountId(), order.playerId(), order.publicId(), OrderEventType.CANCELLED,
          OrderStatus.CANCELLED, now));
    }
    if (posted > 0 || cancelRemainder) {
      tx.publish(new AccountChanged(order.accountId(), order.playerId(), "FILL", now));
    }
    return new Outcome(status, filled, posted, cancelRemainder);
  }

  /** Buying power not held by any open order (§10.2), as of now. */
  BigDecimal freeBuyingPower(long accountId, Instant now) {
    AccountRow acc = accounts.byId(accountId).orElseThrow();
    LocalDate asOf = com.stocks.common.time.Et.date(now);
    Balances b = BalanceCalculator.compute(asOf, accounts.ledgerSums(accountId, asOf), orders.openBuyHolds(accountId),
        acc.restriction());
    return b.buyingPower().max(Money.ZERO).min(b.cashTotal().subtract(b.openBuyHolds()).max(Money.ZERO));
  }

  /** The largest quantity whose principal + fees fit in {@code allowed}: whole shares, or 5 dp for fractional. */
  private BigDecimal affordable(OrderRow order, BigDecimal price, BigDecimal allowed, LocalDate tradeDate,
                                BigDecimal[] sums) {
    int scale = order.fractional() ? Qty.SCALE : 0;
    BigDecimal qty = allowed.max(BigDecimal.ZERO).divide(price, scale, RoundingMode.FLOOR);
    while (qty.signum() > 0) {
      BigDecimal principal = Rounding.principal(order.side(), qty, price);
      Fees f = fees.forExecution(order.side(), tradeDate, sums[0], sums[1], qty, principal);
      if (principal.add(f.total()).compareTo(allowed) <= 0) {
        return Qty.normalize(qty);
      }
      qty = qty.subtract(BigDecimal.ONE.movePointLeft(scale));
    }
    return Qty.ZERO;
  }

  /** §10.3: the hold for the remaining quantity on the order's own basis. */
  private BigDecimal remainingHold(OrderRow order, BigDecimal remaining, BigDecimal fillPrice, LocalDate tradeDate) {
    if (remaining.signum() <= 0 || order.notional() != null) {
      return Money.ZERO;
    }
    BigDecimal basis = order.type() == OrderType.LIMIT ? order.limitPrice()
        : fillPrice.multiply(rules.holdPctRegular()).divide(HUNDRED, 8, RoundingMode.HALF_UP);
    return holds.holdFor(remaining, basis, tradeDate);
  }

  /** {@code Σ(qty·price) / Σ qty} over the order's executions, scale 6 (display only). */
  private BigDecimal averagePrice(long orderId) {
    BigDecimal[] r = jdbc.sql("SELECT COALESCE(SUM(qty * price), 0) AS v, COALESCE(SUM(qty), 0) AS q FROM execution"
            + " WHERE order_id = ?")
        .param(orderId).query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("v"), rs.getBigDecimal("q")}).single();
    return r[1].signum() == 0 ? null : r[0].divide(r[1], 6, RoundingMode.HALF_EVEN);
  }

  /** Cost relieved FIFO and the first lot whose relief is a GFV (null if none). */
  record Relief(BigDecimal cost, LotRow violating) {}

  /** §12.8 step 5 (SELL): relieve lots FIFO, pro-rata cost (HALF_EVEN to cents), update the position. */
  Relief relieve(long accountId, String symbol, BigDecimal qty, LocalDate tradeDate, Instant now) {
    BigDecimal left = qty;
    BigDecimal cost = Money.ZERO;
    LotRow violating = null;
    for (LotRow lot : accounts.openLots(accountId, symbol)) {
      if (left.signum() <= 0) {
        break;
      }
      BigDecimal q = lot.qtyRemaining().min(left);
      BigDecimal c = q.compareTo(lot.qtyRemaining()) == 0 ? lot.costRemaining()
          : lot.costRemaining().multiply(q).divide(lot.qtyRemaining(), Money.SCALE, RoundingMode.HALF_EVEN);
      positions.relieveLot(lot.id(), lot.qtyRemaining().subtract(q), lot.costRemaining().subtract(c), now);
      if (violating == null && GfvService.violates(lot, tradeDate)) {
        violating = lot;
      }
      cost = cost.add(c);
      left = left.subtract(q);
    }
    if (left.signum() > 0) {
      throw new IllegalStateException("Sell of " + qty + " " + symbol + " exceeds the open lots of account "
          + accountId);
    }
    PositionRow pos = accounts.position(accountId, symbol).orElseThrow();
    positions.reduce(accountId, symbol, pos.qty().subtract(qty), pos.costBasis().subtract(cost).max(Money.ZERO), now);
    return new Relief(cost, violating);
  }
}
