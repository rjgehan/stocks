package com.stocks.portfolio;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.testkit.ScriptedMarketData;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The one market-data bean of the WS4 web ITs ({@code @Import({TestClockConfig.class, ScriptedMarketConfig.class})}):
 * a {@link ScriptedMarketData} that knows every symbol those tests use. Every WS4 web IT imports exactly this set, so
 * they share one cached Spring context (one database pool; under {@code -Ppg-it} every extra context holds its own
 * embedded-PostgreSQL connections). {@code @Primary}, so it also wins over WS2's real bean once that lands.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedMarketConfig {

  @Bean
  @Primary
  MarketDataService scriptedMarketData(MutableTimeSource time) {
    return new ScriptedMarketData(time).withAsset("SIMA").withAsset("SIMB").withAsset("AAPL").withAsset("NVDA")
        .withAsset("TSLA");
  }
}
