package com.stocks.contract.trading.dto;

import com.stocks.common.domain.OrderType;
import java.math.BigDecimal;

/**
 * §18.3 {@code GroupLegRequest} (Phase 2, §11.9): one leg of a {@link PlaceOrderGroupRequest}. {@code type}: required
 * for {@code entry} and {@code secondary}; a {@code takeProfit} is a LIMIT; a {@code stopLoss} is a STOP, or a
 * STOP_LIMIT when {@code limitPrice} is set (an explicit type must agree).
 */
public record GroupLegRequest(OrderType type, BigDecimal limitPrice, BigDecimal stopPrice) {}
