package com.stocks.trading.store;

import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import java.time.Instant;

/** One {@code order_event} row (§8.1); {@code detail} is the stored JSON or null. */
public record OrderEventRow(long orderId, int seq, OrderEventType type, OrderStatus statusAfter, Instant occurredAt,
                            String detail) {}
