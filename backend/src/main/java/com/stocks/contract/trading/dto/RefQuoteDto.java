package com.stocks.contract.trading.dto;

import com.stocks.common.domain.DataQuality;
import java.math.BigDecimal;
import java.time.Instant;

/** §18.3 {@code Execution.refQuote}: the quote an execution was priced against (§12.3); {@code clamped} copied. */
public record RefQuoteDto(BigDecimal bid, BigDecimal ask, Long bidSize, Long askSize, BigDecimal last,
                          Instant quoteAt, Instant confirmedAt, String source, DataQuality quality, boolean clamped) {}
