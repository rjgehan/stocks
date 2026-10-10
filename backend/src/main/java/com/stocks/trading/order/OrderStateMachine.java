package com.stocks.trading.order;

import static com.stocks.common.domain.OrderStatus.CANCELLED;
import static com.stocks.common.domain.OrderStatus.EXPIRED;
import static com.stocks.common.domain.OrderStatus.FILLED;
import static com.stocks.common.domain.OrderStatus.PARTIALLY_FILLED;
import static com.stocks.common.domain.OrderStatus.PENDING;
import static com.stocks.common.domain.OrderStatus.REPLACED;
import static com.stocks.common.domain.OrderStatus.WORKING;

import com.stocks.common.domain.OrderStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The order state machine of §11.5, table-driven. Every status change in the engine goes through
 * {@link #transition}; an illegal transition is a bug and throws {@link IllegalStateException} (the engine command
 * rolls back). Timeline events such as {@code PAUSED} or {@code AWAITING_QUOTE} are not states.
 */
public final class OrderStateMachine {

  private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

  static {
    ALLOWED.put(PENDING, EnumSet.of(WORKING, CANCELLED, EXPIRED, REPLACED));
    ALLOWED.put(WORKING, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED, REPLACED));
    ALLOWED.put(PARTIALLY_FILLED, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED));
    for (OrderStatus terminal : EnumSet.of(FILLED, CANCELLED, EXPIRED, REPLACED, OrderStatus.REJECTED)) {
      ALLOWED.put(terminal, EnumSet.noneOf(OrderStatus.class));
    }
    // an API status only (§11.10): never stored, so no stored transition starts or ends there
    ALLOWED.put(OrderStatus.PENDING_CANCEL, EnumSet.noneOf(OrderStatus.class));
  }

  private OrderStateMachine() {}

  public static boolean canTransition(OrderStatus from, OrderStatus to) {
    return ALLOWED.get(from).contains(to);
  }

  /** Returns {@code to} if {@code from → to} is legal; throws otherwise. */
  public static OrderStatus transition(OrderStatus from, OrderStatus to) {
    if (!canTransition(from, to)) {
      throw new IllegalStateException("Illegal order transition " + from + " -> " + to);
    }
    return to;
  }

  /** PENDING, WORKING or PARTIALLY_FILLED (stored), or the API status PENDING_CANCEL (§11.10). */
  public static boolean isOpen(OrderStatus s) {
    return s.isOpen();
  }

  public static boolean isTerminal(OrderStatus s) {
    return !isOpen(s);
  }
}
