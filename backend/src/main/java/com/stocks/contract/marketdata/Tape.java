package com.stocks.contract.marketdata;

import java.util.List;

/**
 * A symbol's recent prints, newest first (§15.10). {@code streaming}: a trade stream currently covers the symbol (so
 * an empty tape means "no prints yet", not "no data"); {@code source} is {@link TapeSource#NONE} when nothing covers
 * it.
 */
public record Tape(String symbol, TapeSource source, boolean streaming, List<TapePrint> prints) {

  public Tape {
    prints = prints == null ? List.of() : List.copyOf(prints);
  }

  /** No trade stream covers the symbol: an honest empty tape. */
  public static Tape none(String symbol) {
    return new Tape(symbol, TapeSource.NONE, false, List.of());
  }
}
