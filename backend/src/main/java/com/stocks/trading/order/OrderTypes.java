package com.stocks.trading.order;

import com.stocks.common.domain.OrderType;

/**
 * What each Phase 1 order type uses and how it fills (§11.1, §12.4, §12.7):
 * <table>
 *   <caption>Order types</caption>
 *   <tr><th>Type</th><th>Prices</th><th>Fills as (after its trigger)</th></tr>
 *   <tr><td>MARKET</td><td>none</td><td>MARKET (collar)</td></tr>
 *   <tr><td>LIMIT</td><td>limitPrice</td><td>LIMIT</td></tr>
 *   <tr><td>STOP</td><td>stopPrice</td><td>MARKET (collar set at the first post-trigger evaluation)</td></tr>
 *   <tr><td>STOP_LIMIT</td><td>stopPrice, limitPrice</td><td>LIMIT (arriving semantics, then resting)</td></tr>
 *   <tr><td>TRAILING_STOP</td><td>trailAmount or trailPercent</td><td>MARKET (collar)</td></tr>
 * </table>
 */
public final class OrderTypes {

  private OrderTypes() {}

  /** STOP, STOP_LIMIT and TRAILING_STOP: armed until {@code TriggerEvaluator} triggers them (§12.7). */
  public static boolean isStop(OrderType type) {
    return type == OrderType.STOP || type == OrderType.STOP_LIMIT || type == OrderType.TRAILING_STOP;
  }

  /** LIMIT and STOP_LIMIT carry a limit price. */
  public static boolean usesLimit(OrderType type) {
    return type == OrderType.LIMIT || type == OrderType.STOP_LIMIT;
  }

  /** STOP and STOP_LIMIT carry a client stop price (a trailing stop's stop is computed, §12.7). */
  public static boolean usesStop(OrderType type) {
    return type == OrderType.STOP || type == OrderType.STOP_LIMIT;
  }

  public static boolean usesTrail(OrderType type) {
    return type == OrderType.TRAILING_STOP;
  }

  /** The type the fill model works a (triggered) order as: LIMIT for LIMIT and STOP_LIMIT, else MARKET. */
  public static OrderType fillsAs(OrderType type) {
    return usesLimit(type) ? OrderType.LIMIT : OrderType.MARKET;
  }
}
