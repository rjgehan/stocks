package com.stocks.realtime.stream;

import com.stocks.contract.marketdata.MarketUpdateListener;
import org.springframework.stereotype.Component;

/**
 * Quote changes for the SSE stream (§19.1): a {@link MarketUpdateListener} like {@code QuoteFanout}, so
 * {@code QuoteBook} calls it for every new quote or print. It only marks the symbol dirty on the streams that watch
 * it (never blocks, §5.3); each stream's sender sends the latest quote at most once per {@code quote-interval}. The
 * streams' LIST leases ({@code sse:<connectionId>}) keep the symbols subscribed upstream.
 */
@Component
public class StreamQuotes implements MarketUpdateListener {

  private final StreamHub hub;

  public StreamQuotes(StreamHub hub) {
    this.hub = hub;
  }

  @Override
  public void onMarketUpdate(String symbol) {
    if (hub.watches(symbol)) {
      hub.quoteChanged(symbol);
    }
  }
}
