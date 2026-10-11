package com.stocks.contract.trading.dto;

import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Set;

/**
 * §18.3 {@code PlaceOrderGroupRequest} ({@code POST /order-groups}, {@code /order-groups/preview}, MCP
 * {@code place_order_group}; Phase 2, §11.9). {@code side}: OCO legs (default SELL); for the other types the entry
 * side, BUY only in wave A. {@code qty}: whole shares, every leg. {@code timeInForce}: every leg (null = DAY; a MARKET
 * entry is DAY). Like {@link PlaceOrderRequest}, required members use {@code @Schema}, not Bean Validation: a missing
 * {@code clientOrderId} is {@code CLIENT_ORDER_ID_INVALID} (422) and preview accepts none.
 */
public record PlaceOrderGroupRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientOrderId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OrderGroupType type,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String symbol,
    Side side,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal qty,
    TimeInForce timeInForce, GroupLegRequest entry, GroupLegRequest takeProfit, GroupLegRequest stopLoss,
    GroupLegRequest secondary, String rationale, Boolean shareNote, Boolean doNotReduce, Set<String> acknowledge) {}
