package com.stocks.marketdata.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.domain.Crypto;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fundamentals (§15.5): Finnhub {@code profile2} + {@code metric} (or the SIM), cached 24 h; failures cached 10 min. */
public class FundamentalsService {

  private static final Logger log = LoggerFactory.getLogger(FundamentalsService.class);

  private final MarketDataProvider provider;
  private final Cache<String, Optional<Fundamentals>> cache = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofHours(24)).maximumSize(5_000).build();
  private final Cache<String, Boolean> failures = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(10)).maximumSize(5_000).build();

  /** {@code provider} may be null (no source: always empty). */
  public FundamentalsService(MarketDataProvider provider) {
    this.provider = provider;
  }

  public Optional<Fundamentals> fundamentals(String symbol) {
    if (provider == null || Crypto.isCrypto(symbol) || OptionSymbol.isOption(symbol) || failures.getIfPresent(symbol) != null) {   // none for coins
      return Optional.empty();
    }
    Optional<Fundamentals> cached = cache.getIfPresent(symbol);
    if (cached != null) {
      return cached;
    }
    try {
      Optional<Fundamentals> f = provider.fundamentals(symbol);
      cache.put(symbol, f);
      return f;
    } catch (ProviderException | UnsupportedOperationException e) {
      log.debug("fundamentals {} failed: {}", symbol, e.getMessage());
      failures.put(symbol, Boolean.TRUE);
      return Optional.empty();
    }
  }
}
