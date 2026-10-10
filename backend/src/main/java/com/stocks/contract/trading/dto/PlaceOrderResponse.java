package com.stocks.contract.trading.dto;

import com.stocks.common.error.Warning;
import java.math.BigDecimal;
import java.util.List;

/** §18.3 {@code PlaceOrderResponse} (§18.4 example). {@code replayed}: an idempotent replay (§11.7). */
public record PlaceOrderResponse(OrderDto order, EstimateDto estimate, BigDecimal buyingPowerBefore,
                                 BigDecimal buyingPowerAfter, List<Warning> warnings, boolean replayed) {}
