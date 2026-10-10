package com.stocks.trading.engine;

import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.events.OrderChanged;
import com.stocks.common.money.Money;
import com.stocks.common.money.Rounding;
import com.stocks.trading.engine.write.OrderWriter;
import com.stocks.trading.order.HoldCalculator;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Order operations that other engine-side packages ({@code trading.corpactions}) run inside an engine command's
 * transaction, without reaching into {@link OrderHandler}. Engine thread only (§4 rule 1).
 */
@Component
public class EngineOrderOps {

  /** One order a cash dividend adjusted (or cancelled, when its price would reach zero) under FINRA 5330 (§14). */
  public record DividendAdjustment(long orderId, String orderPublicId, long accountId, long playerId,
                                   boolean cancelled) {}

  private final OrderHandler orders;
  private final OrderStore orderStore;
  private final OrderWriter writer;
  private final HoldCalculator holds;
  private final TradingClock clock;
  private final JsonMapper json;

  EngineOrderOps(OrderHandler orders, OrderStore orderStore, OrderWriter writer, HoldCalculator holds,
                 TradingClock clock, JsonMapper json) {
    this.orders = orders;
    this.orderStore = orderStore;
    this.writer = writer;
    this.holds = holds;
    this.clock = clock;
    this.json = json;
  }

  /**
   * Cancels every open order on {@code symbol} (all accounts) with {@code reason}, e.g. {@code CORPORATE_ACTION}
   * before a split or a name change (§14); {@code actorPlayerId} 0 = the system. Returns the cancelled orders.
   */
  public List<OrderRow> cancelAllOnSymbol(String symbol, CancelReason reason, long actorPlayerId, Instant now,
                                          EngineTx tx) {
    return cancelAllOnSymbol(symbol, reason, actorPlayerId, now, null, tx);
  }

  /**
   * As {@link #cancelAllOnSymbol(String, CancelReason, long, Instant, EngineTx)}, but only the orders received before
   * {@code receivedBeforeOrNull} (null: all): a split applied after its ex cut-off keeps the orders entered after it,
   * which were priced post-split (§14).
   */
  public List<OrderRow> cancelAllOnSymbol(String symbol, CancelReason reason, long actorPlayerId, Instant now,
                                          Instant receivedBeforeOrNull, EngineTx tx) {
    EngineThread.assertCurrent();
    List<OrderRow> open = orderStore.openBySymbol(symbol).stream()
        .filter(r -> receivedBeforeOrNull == null || r.receivedAt().isBefore(receivedBeforeOrNull)).toList();
    for (OrderRow r : open) {
      orders.cancelOpen(r, reason, actorPlayerId, now, tx);
    }
    return open;
  }

  /**
   * FINRA Rule 5330 (§14, Phase 2): on a cash dividend's ex-date, every open order on {@code symbol} received before
   * the ex cut-off and not marked "do not reduce" has its prices reduced by {@code dividend}: a BUY LIMIT's limit, an
   * untriggered SELL STOP's stop, an untriggered SELL STOP_LIMIT's stop and limit, each rounded down to the tick
   * ({@code TICK_DOWN(price − dividend)}); a price that would reach zero cancels the order ({@code CORPORATE_ACTION}).
   * A BUY LIMIT's hold follows its new limit, never rising; a leg of a BUY OCO pair keeps the pair's shared
   * reservation. Each adjusted order gets a {@code PRICE_ADJUSTED} event and an {@code OrderChanged}.
   */
  public List<DividendAdjustment> adjustForCashDividend(String symbol, BigDecimal dividend, Instant receivedBefore,
                                                        String corporateActionId, Instant now, EngineTx tx) {
    EngineThread.assertCurrent();
    List<DividendAdjustment> out = new ArrayList<>();
    if (dividend == null || dividend.signum() <= 0) {
      return out;
    }
    for (OrderRow r : orderStore.openBySymbol(symbol)) {
      if (r.doNotReduce() || !r.receivedAt().isBefore(receivedBefore) || !adjustable(r)) {
        continue;
      }
      boolean buyLimit = r.side() == Side.BUY && r.type() == OrderType.LIMIT;
      BigDecimal limitAfter = buyLimit || r.type() == OrderType.STOP_LIMIT ? reduce(r.limitPrice(), dividend)
          : r.limitPrice();
      BigDecimal stopAfter = r.side() == Side.SELL ? reduce(r.stopPrice(), dividend) : r.stopPrice();
      boolean toZero = (limitAfter != null && limitAfter.signum() <= 0) || (stopAfter != null && stopAfter.signum() <= 0);
      if (toZero) {
        orders.cancelOpen(r, CancelReason.CORPORATE_ACTION, 0, now, tx);
        out.add(new DividendAdjustment(r.id(), r.publicId(), r.accountId(), r.playerId(), true));
        continue;
      }
      BigDecimal reserved = r.reservedCash();
      if (buyLimit && reserved.signum() > 0 && !GroupHandler.inPair(r) && r.remainingQty() != null) {
        reserved = reserved.min(holds.holdFor(r.remainingQty(), limitAfter, clock.tradeDate(now)));
      }
      writer.adjustPrices(r.id(), limitAfter, stopAfter, reserved);
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("reason", "DIVIDEND");
      detail.put("corporateActionId", corporateActionId);
      detail.put("dividend", dividend.stripTrailingZeros().toPlainString());
      if (limitAfter != null && r.limitPrice() != null && limitAfter.compareTo(r.limitPrice()) != 0) {
        detail.put("limitBefore", r.limitPrice());
        detail.put("limitAfter", limitAfter);
      }
      if (stopAfter != null && r.stopPrice() != null && stopAfter.compareTo(r.stopPrice()) != 0) {
        detail.put("stopBefore", r.stopPrice());
        detail.put("stopAfter", stopAfter);
      }
      if (reserved.compareTo(r.reservedCash()) != 0) {
        detail.put("holdBefore", r.reservedCash());
        detail.put("holdAfter", reserved);
      }
      writer.event(r.id(), OrderEventType.PRICE_ADJUSTED, r.status(), now, json.writeValueAsString(detail));
      tx.publish(new OrderChanged(r.accountId(), r.playerId(), r.publicId(), OrderEventType.PRICE_ADJUSTED,
          r.apiStatus(), now));
      out.add(new DividendAdjustment(r.id(), r.publicId(), r.accountId(), r.playerId(), false));
    }
    return out;
  }

  /** The orders FINRA 5330 reduces: BUY LIMIT, and SELL STOP / STOP_LIMIT that have not triggered yet. */
  static boolean adjustable(OrderRow r) {
    if (r.side() == Side.BUY) {
      return r.type() == OrderType.LIMIT && r.limitPrice() != null;
    }
    return (r.type() == OrderType.STOP || r.type() == OrderType.STOP_LIMIT) && !r.triggered()
        && r.stopPrice() != null;
  }

  /** {@code TICK_DOWN(price − dividend)}: the next lower price on the tick (null stays null). */
  static BigDecimal reduce(BigDecimal price, BigDecimal dividend) {
    if (price == null) {
      return null;
    }
    BigDecimal raw = price.subtract(dividend);
    return raw.signum() <= 0 ? Money.ZERO : Rounding.TICK_DOWN.apply(raw);
  }
}
