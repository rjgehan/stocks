package com.stocks.trading.engine;

import com.stocks.common.domain.CancelReason;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Order operations that other engine-side packages ({@code trading.corpactions}) run inside an engine command's
 * transaction, without reaching into {@link OrderHandler}. Engine thread only (§4 rule 1).
 */
@Component
public class EngineOrderOps {

  private final OrderHandler orders;
  private final OrderStore orderStore;

  EngineOrderOps(OrderHandler orders, OrderStore orderStore) {
    this.orders = orders;
    this.orderStore = orderStore;
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
}
