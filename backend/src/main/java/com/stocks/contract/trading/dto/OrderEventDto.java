package com.stocks.contract.trading.dto;

import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import java.time.Instant;
import java.util.Map;

/** §18.3 {@code OrderEvent}: one row of the order timeline; {@code detail} is the stored JSON or null. */
public record OrderEventDto(int seq, OrderEventType type, OrderStatus statusAfter, Instant at,
                            Map<String, Object> detail) {}
