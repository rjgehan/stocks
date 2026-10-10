package com.stocks.contract.marketdata;

/**
 * {@code QuoteBook} calls every bean of this type (injected as {@code List<MarketUpdateListener>}): the engine and
 * WS5's {@code QuoteFanout} (§15.5). Implementations MUST NOT block.
 */
public interface MarketUpdateListener {
  void onMarketUpdate(String symbol);
}
