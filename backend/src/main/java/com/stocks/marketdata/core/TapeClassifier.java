package com.stocks.marketdata.core;

import com.stocks.contract.marketdata.TapeSide;
import java.math.BigDecimal;

/**
 * Colors a Time &amp; Sales print against the quote in force when it arrived (§15.10, pure): at or above the ask is
 * {@code AT_ASK}, at or below the bid {@code AT_BID}, strictly inside {@code BETWEEN}; without a two-sided quote
 * (either side missing or zero, or a crossed quote) {@code UNKNOWN}. On the hybrid profile the quote is IEX's while
 * SIP prints come from every venue, so the color is an approximation of the NBBO side (documented in the UI).
 */
public final class TapeClassifier {

  private TapeClassifier() {}

  public static TapeSide classify(BigDecimal price, BigDecimal bid, BigDecimal ask) {
    if (price == null || bid == null || ask == null || bid.signum() <= 0 || ask.signum() <= 0
        || ask.compareTo(bid) < 0) {
      return TapeSide.UNKNOWN;
    }
    if (price.compareTo(ask) >= 0) {
      return TapeSide.AT_ASK;
    }
    if (price.compareTo(bid) <= 0) {
      return TapeSide.AT_BID;
    }
    return TapeSide.BETWEEN;
  }
}
