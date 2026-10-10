package com.stocks.contract.marketdata;

import com.stocks.common.domain.DataQuality;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * The quote the engine may execute against (§12.3, anti-look-ahead): confirmed at or after the order's
 * {@code eligible_at}. {@code clamped}: the IEX spread was clamped by {@code ExecutableQuoteBuilder}.
 */
public record ExecQuote(String symbol, BigDecimal bid, long bidSize, BigDecimal ask, long askSize, BigDecimal last,
    Instant quoteTs, Instant confirmedAt, DataQuality quality, String source, boolean clamped) {

  public BigDecimal spread() {
    return ask.subtract(bid);
  }

  public BigDecimal mid() {
    return bid.add(ask).divide(BigDecimal.TWO, 6, RoundingMode.HALF_EVEN);
  }
}
