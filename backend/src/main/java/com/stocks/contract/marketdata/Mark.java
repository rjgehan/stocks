package com.stocks.contract.marketdata;

import com.stocks.common.domain.DataQuality;
import java.math.BigDecimal;
import java.time.Instant;

/** A live mark used for equity (§15.5), split-adjusted. {@code price} is null only when quality is {@code NONE}. */
public record Mark(BigDecimal price, DataQuality quality, Instant asOf, MarkBasis basis) {}
