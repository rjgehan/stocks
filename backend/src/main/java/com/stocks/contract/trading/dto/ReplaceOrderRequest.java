package com.stocks.contract.trading.dto;

import com.stocks.common.domain.TimeInForce;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * §5.3 / §18.3 {@code ReplaceOrderRequest} ({@code PATCH /orders/{orderId}}, MCP {@code replace_order}): cancel-replace
 * with a NEW {@code clientOrderId} (§11.6). Null = unchanged. On a group leg the §11.9 replace rules apply.
 */
public record ReplaceOrderRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientOrderId,
    BigDecimal qty, BigDecimal limitPrice, BigDecimal stopPrice, BigDecimal trailAmount, BigDecimal trailPercent,
    TimeInForce timeInForce, Boolean extendedHours, String rationale, Boolean doNotReduce) {

  /** A request without {@code doNotReduce} (the Phase 1 shape: unchanged). */
  public ReplaceOrderRequest(String clientOrderId, BigDecimal qty, BigDecimal limitPrice, BigDecimal stopPrice,
                             BigDecimal trailAmount, BigDecimal trailPercent, TimeInForce timeInForce,
                             Boolean extendedHours, String rationale) {
    this(clientOrderId, qty, limitPrice, stopPrice, trailAmount, trailPercent, timeInForce, extendedHours, rationale,
        null);
  }
}
