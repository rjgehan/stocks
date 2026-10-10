package com.stocks.trading.engine;

import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.trading.order.OrderTypes;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.store.OrderRow;
import org.springframework.stereotype.Component;

/**
 * The in-memory side of an open order (§12.2): its {@link OpenOrderIndex} entry, an armed stop's trigger state and
 * its market-data lease. Called only after commit ({@link EngineTx#afterCommit}). A held group child (§11.9) is never
 * indexed: it is not evaluated until its entry releases it.
 */
@Component
class OrderTracker {

  private final OpenOrderIndex index;
  private final MarketDataService marketData;
  private final TradingClock clock;

  OrderTracker(OpenOrderIndex index, MarketDataService marketData, TradingClock clock) {
    this.index = index;
    this.marketData = marketData;
    this.clock = clock;
  }

  /**
   * Index the order and lease its symbol so market data keeps it fresh (§12.2, §15.5): an armed stop holds an
   * {@code ORDER_TRIGGER} lease (it needs prints), everything else {@code ORDER_RESTING}. Held children are skipped.
   */
  void track(OrderRow row) {
    if (row.held() || !row.isOpen()) {
      return;
    }
    OpenOrderIndex.Entry e = index.put(row);
    if (OrderTypes.isStop(row.type()) && e.trigger == null) {
      e.trigger = TriggerEvaluator.stateFor(row, clock.now());
    }
    if (e.lease == null) {
      LeasePriority priority = OrderTypes.isStop(row.type()) && !row.triggered() ? LeasePriority.ORDER_TRIGGER
          : LeasePriority.ORDER_RESTING;
      try {
        e.lease = marketData.lease(row.symbol(), priority, "order:" + row.publicId());
      } catch (RuntimeException ignored) {
        e.lease = null;   // market data unavailable: evaluation requests refreshes instead
      }
    }
  }

  /** Forget a terminal order and release its lease. */
  void untrack(long orderId) {
    OpenOrderIndex.Entry e = index.remove(orderId);
    if (e != null && e.lease != null) {
      try {
        e.lease.close();
      } catch (RuntimeException ignored) {
        // best effort
      }
    }
  }

  /** The index entry of an open, tracked order, or null. */
  OpenOrderIndex.Entry entry(long orderId) {
    return index.get(orderId);
  }
}
