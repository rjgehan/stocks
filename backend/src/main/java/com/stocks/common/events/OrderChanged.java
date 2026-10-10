package com.stocks.common.events;

import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import java.time.Instant;

/** An order changed state (§5.3). {@code orderId} is the public id ({@code ord_…}). */
public record OrderChanged(long accountId, long playerId, String orderId, OrderEventType eventType, OrderStatus status,
                           Instant at) {}
