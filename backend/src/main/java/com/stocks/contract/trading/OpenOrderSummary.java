package com.stocks.contract.trading;

import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;

/** An open order, for leases and the UI ({@code OrderQueries.openOrdersBySymbol}). */
public record OpenOrderSummary(String orderId, long accountId, long playerId, String symbol, Side side, OrderType type,
    OrderStatus status, boolean triggered, boolean extendedHours) {}
