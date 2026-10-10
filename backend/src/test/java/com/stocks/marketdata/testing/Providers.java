package com.stocks.marketdata.testing;

import com.stocks.marketdata.config.MarketDataProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Market-data properties pointing at fake servers. */
public final class Providers {

  private Providers() {}

  public static MarketDataProperties.Alpaca alpaca(String httpUrl, String streamUrl) {
    return new MarketDataProperties.Alpaca("test-key", "test-secret", "iex", httpUrl, httpUrl, streamUrl, 30, 180, 100,
        Duration.ofSeconds(2), Duration.ofSeconds(5));
  }

  public static MarketDataProperties.Finnhub finnhub(String httpUrl, String wsUrl) {
    return new MarketDataProperties.Finnhub("fh-token", httpUrl, wsUrl, 50, 55, true);
  }

  public static MarketDataProperties hybrid(MarketDataProperties.Alpaca alpaca, MarketDataProperties.Finnhub finnhub) {
    return new MarketDataProperties("hybrid", false, List.of("SPY"), Map.of(), MarketDataProperties.Poll.defaults(),
        alpaca, finnhub == null ? MarketDataProperties.Finnhub.defaults() : finnhub,
        MarketDataProperties.Sim.defaults());
  }
}
