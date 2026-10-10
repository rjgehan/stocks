package com.stocks.contract.marketdata.model;

import com.stocks.common.domain.DataQuality;
import java.math.BigDecimal;
import java.time.Instant;

/** A top-of-book quote. Sizes are in shares (the adapter normalizes round lots, §15.6). */
public record Quote(String symbol, BigDecimal bid, long bidSize, BigDecimal ask, long askSize, String bidVenue,
                    String askVenue, Instant ts, Instant observedAt, DataQuality quality, String source) {

  /** Both sides present and positive, and not crossed. */
  public boolean twoSided() {
    return bid != null && ask != null && bid.signum() > 0 && ask.signum() > 0 && ask.compareTo(bid) >= 0;
  }
}
