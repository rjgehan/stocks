package com.stocks.contract.trading.dto;

import com.stocks.common.domain.OrderGroupStatus;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * §18.3 {@code OrderGroup} (Phase 2, §11.9). {@code legs}: the current legs (REPLACED ones left out) in role order
 * ENTRY, TAKE_PROFIT, STOP_LOSS, SECONDARY, in the list shape ({@code executions} and {@code events} null).
 */
public record OrderGroupDto(String groupId, String clientOrderId, OrderGroupType type, String symbol, BigDecimal qty,
                            TimeInForce timeInForce, OrderGroupStatus status, Instant createdAt, Instant updatedAt,
                            List<OrderDto> legs) {}
