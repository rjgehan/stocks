package com.stocks.marketdata.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * News (§15.5): the first provider that answers (Alpaca/Benzinga, then Finnhub {@code company-news}; the SIM writes
 * daily recaps), cached 60 s. Returns an empty list when no source is available, so the UI can hide the tab.
 */
public class NewsService {

  private static final Logger log = LoggerFactory.getLogger(NewsService.class);

  private record Key(Set<String> symbols, int limit, Instant before) {}

  private final List<MarketDataProvider> providers;
  private final Supplier<Boolean> primaryAllowed;
  private final Cache<Key, List<NewsItem>> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60))
      .maximumSize(500).build();

  public NewsService(List<MarketDataProvider> providers, Supplier<Boolean> primaryAllowed) {
    this.providers = List.copyOf(providers);
    this.primaryAllowed = primaryAllowed;
  }

  public boolean available() {
    return !providers.isEmpty();
  }

  public List<NewsItem> news(Set<String> symbols, int limit, Instant before) {
    Key key = new Key(new TreeSet<>(symbols), limit, before == null ? null : before.truncatedTo(ChronoUnit.MINUTES));
    return cache.get(key, k -> load(k.symbols(), k.limit(), before));
  }

  private List<NewsItem> load(Set<String> symbols, int limit, Instant before) {
    for (int i = 0; i < providers.size(); i++) {
      MarketDataProvider p = providers.get(i);
      if (i == 0 && !primaryAllowed.get() || !p.capabilities().news()) {
        continue;
      }
      if (symbols.isEmpty() && p.id().equals("finnhub")) {
        continue;                                    // company-news needs a symbol
      }
      try {
        List<NewsItem> items = p.news(symbols, limit, before);
        if (!items.isEmpty() || i == providers.size() - 1) {
          return items.stream().limit(limit).toList();
        }
      } catch (ProviderException | UnsupportedOperationException e) {
        log.debug("news from {} failed: {}", p.id(), e.getMessage());
      }
    }
    return List.of();
  }
}
