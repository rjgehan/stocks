package com.stocks.contract.trading.dto;

import com.stocks.common.domain.DataQuality;
import java.math.BigDecimal;
import java.time.Instant;

/** §18.3 {@code Position}. {@code dayReturn} null when there is no previous-EOD market value. */
public record PositionDto(String symbol, String name, BigDecimal qty, BigDecimal availableQty, BigDecimal avgCost,
                          BigDecimal costBasis, BigDecimal last, BigDecimal mark, DataQuality markQuality,
                          BigDecimal marketValue, BigDecimal unrealizedPnl, BigDecimal unrealizedReturn,
                          BigDecimal dayPnl, BigDecimal dayReturn, BigDecimal weight, boolean fractionable,
                          Instant openedAt) {}
