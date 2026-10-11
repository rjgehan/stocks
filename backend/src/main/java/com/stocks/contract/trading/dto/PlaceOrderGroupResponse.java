package com.stocks.contract.trading.dto;

import com.stocks.common.error.Warning;
import java.math.BigDecimal;
import java.util.List;

/**
 * §18.3 {@code PlaceOrderGroupResponse} (Phase 2, §11.9). {@code estimate}: the entry's, or the OCO's take-profit
 * leg's; {@code warnings}: every leg's, each message prefixed with the leg; {@code replayed}: an idempotent replay.
 */
public record PlaceOrderGroupResponse(OrderGroupDto group, EstimateDto estimate, BigDecimal buyingPowerBefore,
                                      BigDecimal buyingPowerAfter, List<Warning> warnings, boolean replayed) {}
