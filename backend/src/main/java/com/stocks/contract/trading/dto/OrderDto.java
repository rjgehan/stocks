package com.stocks.contract.trading.dto;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * §18.3 {@code Order}. {@code executions} and {@code events} are filled only by the detail endpoints
 * ({@code GET /orders/{id}}, {@code /orders/by-client-id/{id}}, MCP {@code get_order}); elsewhere they are
 * {@code null} (present, §6.3). {@code fees} is the order's fee total.
 */
public record OrderDto(String orderId, String clientOrderId, String accountId, String symbol, Side side,
                       OrderType type, BigDecimal qty, BigDecimal notional, boolean fractional, BigDecimal limitPrice,
                       BigDecimal stopPrice, BigDecimal trailAmount, BigDecimal trailPercent,
                       BigDecimal watermarkPrice, BigDecimal collarPrice, TimeInForce timeInForce,
                       boolean extendedHours, OrderStatus status, WaitReason waitReason, String statusReason,
                       boolean triggered, BigDecimal filledQty, BigDecimal filledPrincipal, BigDecimal avgFillPrice,
                       BigDecimal fees, BigDecimal reservedCash, BigDecimal reservedQty, LocalDate sessionDate,
                       Instant receivedAt, Instant acceptedAt, Instant eligibleAt, Instant activatedAt,
                       Instant triggeredAt, Instant expiresAt, Instant doneAt, String replacesOrderId,
                       String replacedByOrderId, Channel channel, String rationale, List<ExecutionDto> executions,
                       List<OrderEventDto> events) {}
