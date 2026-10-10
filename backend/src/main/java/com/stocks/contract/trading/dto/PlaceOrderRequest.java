package com.stocks.contract.trading.dto;

import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Set;

/**
 * §5.3 / §18.3 {@code PlaceOrderRequest} ({@code POST /orders}, {@code /orders/preview}, MCP {@code place_order}).
 * Exactly one of {@code qty} and {@code notional}. {@code timeInForce} null = DAY; {@code extendedHours} null = false
 * (a wrapper, not {@code boolean}: Jackson 3 fails a missing primitive creator property, and bots omit optional
 * members); {@code rationale} required for AI (1..1000); {@code shareNote} humans only; {@code acknowledge} may
 * contain {@code "GFV_RISK"}.
 *
 * <p>Required fields are marked with {@code @Schema(requiredMode = REQUIRED)}, not Bean Validation: a missing or bad
 * {@code clientOrderId} is the domain error {@code CLIENT_ORDER_ID_INVALID} (422, §11.2 check 5), not a 400, and
 * preview accepts a request without one.
 */
public record PlaceOrderRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientOrderId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String symbol,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Side side,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OrderType type,
    BigDecimal qty, BigDecimal notional, BigDecimal limitPrice, BigDecimal stopPrice,
    BigDecimal trailAmount, BigDecimal trailPercent, TimeInForce timeInForce, Boolean extendedHours,
    String rationale, Boolean shareNote, Set<String> acknowledge) {

  /** {@code extendedHours}, defaulting to false. */
  public boolean extendedHoursOrDefault() {
    return Boolean.TRUE.equals(extendedHours);
  }
}
