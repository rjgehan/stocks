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
 * entry is DAY). {@code rationale}: one note for the group, required for AI; public (public mode, §16.7);
 * {@code shareNote} deprecated and ignored. Like {@link PlaceOrderRequest}, required members use {@code @Schema}, not
 * Bean Validation: a missing {@code clientOrderId} is {@code CLIENT_ORDER_ID_INVALID} (422) and preview accepts none.
 */
public record PlaceOrderGroupRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientOrderId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OrderGroupType type,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String symbol,
    Side side,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal qty,
    TimeInForce timeInForce, GroupLegRequest entry, GroupLegRequest takeProfit, GroupLegRequest stopLoss,
    GroupLegRequest secondary,
    @Schema(description = "One note for the group (1-1000 characters): required for AI players; public, every player "
        + "sees it with the orders")
    String rationale,
    @Schema(description = "Deprecated and ignored: every new note is public (public mode)", deprecated = true)
    Boolean shareNote,
    Boolean doNotReduce, Set<String> acknowledge) {}
