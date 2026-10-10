package com.stocks.contract.trading.dto;

import com.stocks.common.domain.DataQuality;
import java.math.BigDecimal;
import java.time.Instant;

/** §18.3 {@code Estimate}: the pre-trade estimate on place and preview responses. */
public record EstimateDto(BigDecimal price, BigDecimal qty, BigDecimal principal, FeesDto fees, BigDecimal hold,
                          Instant quoteAsOf, DataQuality quoteQuality) {}
