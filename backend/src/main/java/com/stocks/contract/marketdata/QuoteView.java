package com.stocks.contract.marketdata;

import com.stocks.common.domain.DataQuality;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A quote as the UI and bots see it, composed by {@code QuoteBook} on read (§15.5). {@code prevClose} is
 * split-adjusted. {@code quality} is {@code NONE} when nothing is known. Mapped to {@code QuoteDto} by WS2's
 * {@code QuoteDtoMapper}.
 */
public record QuoteView(String symbol, BigDecimal bid, Long bidSize, BigDecimal ask, Long askSize,
    BigDecimal last, Long lastSize, Instant lastAt, BigDecimal prevClose, BigDecimal change,
    BigDecimal changePct, BigDecimal dayOpen, BigDecimal dayHigh, BigDecimal dayLow, Long volume,
    boolean volumeIsPartial, ExtendedLine extended, Instant asOf, Instant confirmedAt, DataQuality quality,
    String source, boolean halted) {

  /** A view for a symbol nothing is known about: every price null, quality {@code NONE}. */
  public static QuoteView none(String symbol, String source) {
    return new QuoteView(symbol, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        false, null, null, null, DataQuality.NONE, source, false);
  }
}
