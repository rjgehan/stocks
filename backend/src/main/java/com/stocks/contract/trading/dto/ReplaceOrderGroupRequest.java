package com.stocks.contract.trading.dto;

import com.stocks.common.domain.TimeInForce;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * §18.3 {@code ReplaceOrderGroupRequest} ({@code PATCH /order-groups/{groupId}}; Phase 2, §11.9): cancel-replace of
 * every changed, unfilled leg in one step, with a NEW group {@code clientOrderId}. Null members are unchanged;
 * {@code qty} only while no leg has filled.
 */
public record ReplaceOrderGroupRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientOrderId,
    BigDecimal qty, TimeInForce timeInForce, GroupLegChangeDto entry, GroupLegChangeDto takeProfit,
    GroupLegChangeDto stopLoss, GroupLegChangeDto secondary, String rationale) {}
