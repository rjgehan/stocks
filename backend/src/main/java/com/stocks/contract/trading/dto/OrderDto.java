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
 *
 * <p>Phase 2: {@code group} is the leg's order group (§11.9, null for a single order); {@code doNotReduce} opts out of
 * the FINRA 5330 ex-dividend adjustment (§14); {@code cancelRequestedAt} is set by a user cancel under cancel latency
 * (§11.10), and {@code status} reads {@code PENDING_CANCEL} while such an order is still open.
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
                       List<OrderEventDto> events, OrderGroupRefDto group, boolean doNotReduce,
                       Instant cancelRequestedAt) {

  /** The same order with {@code executions} and {@code events} replaced (the list shape passes nulls). */
  public OrderDto withDetail(List<ExecutionDto> executions, List<OrderEventDto> events) {
    return new OrderDto(orderId, clientOrderId, accountId, symbol, side, type, qty, notional, fractional, limitPrice,
        stopPrice, trailAmount, trailPercent, watermarkPrice, collarPrice, timeInForce, extendedHours, status,
        waitReason, statusReason, triggered, filledQty, filledPrincipal, avgFillPrice, fees, reservedCash,
        reservedQty, sessionDate, receivedAt, acceptedAt, eligibleAt, activatedAt, triggeredAt, expiresAt, doneAt,
        replacesOrderId, replacedByOrderId, channel, rationale, executions, events, group, doNotReduce,
        cancelRequestedAt);
  }
}
