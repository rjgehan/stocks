package com.stocks.contract.portfolio.dto;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.contract.portfolio.dto.PublicTradeDto.UntrustedRationaleDto;
import com.stocks.contract.trading.dto.OrderGroupRefDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * §18.3 {@code PublicOrder}: one order as every player sees it (public mode, §16.7; {@code GET
 * /players/{handle}/orders}, MCP {@code get_player_orders}). {@code channel} is public (public mode); never
 * {@code clientOrderId}, account ids, key or OAuth client ids, the MCP client name, IPs or holds.
 * {@code manualSessionId}: the manual-console session (§20.8) that placed it, else null. {@code fills} oldest first;
 * {@code untrusted.rationale} the full note when it is public, else null.
 */
public record PublicOrderDto(String orderId, int runNo, String symbol, Side side, OrderType type, BigDecimal qty,
                             BigDecimal notional, boolean fractional, BigDecimal limitPrice, BigDecimal stopPrice,
                             BigDecimal trailAmount, BigDecimal trailPercent, TimeInForce timeInForce,
                             boolean extendedHours, OrderStatus status, WaitReason waitReason, String statusReason,
                             BigDecimal filledQty, BigDecimal avgFillPrice, BigDecimal fees, Instant receivedAt,
                             Instant expiresAt, Instant doneAt, String replacesOrderId, String replacedByOrderId,
                             OrderGroupRefDto group, Channel channel, boolean viaApi, String manualSessionId,
                             List<PublicFillDto> fills, UntrustedRationaleDto untrusted) {

  /** One execution of the order: {@code fees} the SEC + TAF + CAT total; {@code realizedPnl} null on a buy. */
  public record PublicFillDto(String executionId, Instant executedAt, BigDecimal qty, BigDecimal price,
                              BigDecimal fees, BigDecimal realizedPnl) {}
}
