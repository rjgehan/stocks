package com.stocks.marketdata.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * News (§15.5, Phase 2 merge §15.10). The primary source (Alpaca/Benzinga; the SIM writes daily recaps) is cached
 * {@link #PRIMARY_TTL}. For symbol news (1–{@link #MERGE_MAX_SYMBOLS} symbols) the secondary sources (Finnhub
 * {@code company-news}, one call per symbol) are merged in, cached {@link #SECONDARY_TTL} so a busy quote page costs
 * Finnhub at most one call per symbol every 10 minutes; general news (no symbols) is the primary's only. Items are
 * deduplicated by URL and by headline (the same story syndicated by both sources), newest first. Returns an empty
 * list when no source is available, so the UI can hide the tab. A failing source is skipped, never an error.
 */
public class NewsService {

  private static final Logger log = LoggerFactory.getLogger(NewsService.class);
  static final Duration PRIMARY_TTL = Duration.ofSeconds(60);
  static final Duration SECONDARY_TTL = Duration.ofMinutes(10);
  static final int MERGE_MAX_SYMBOLS = 3;

  private record Key(String provider, Set<String> symbols, int limit, Instant before) {}

  private final List<MarketDataProvider> providers;
  private final Supplier<Boolean> primaryAllowed;
  private final Cache<Key, List<NewsItem>> primary = Caffeine.newBuilder().expireAfterWrite(PRIMARY_TTL)
      .maximumSize(500).build();
  private final Cache<Key, List<NewsItem>> secondary = Caffeine.newBuilder().expireAfterWrite(SECONDARY_TTL)
      .maximumSize(500).build();

  public NewsService(List<MarketDataProvider> providers, Supplier<Boolean> primaryAllowed) {
    this.providers = List.copyOf(providers);
    this.primaryAllowed = primaryAllowed;
  }

  public boolean available() {
    return !providers.isEmpty();
  }

  public List<NewsItem> news(Set<String> symbols, int limit, Instant before) {
    Set<String> syms = new TreeSet<>(symbols);
    Instant beforeKey = before == null ? null : before.truncatedTo(ChronoUnit.MINUTES);
    List<NewsItem> all = new ArrayList<>();
    boolean primaryAnswered = false;
    for (int i = 0; i < providers.size(); i++) {
      MarketDataProvider p = providers.get(i);
      if (i == 0 && !primaryAllowed.get() || !p.capabilities().news()) {
        continue;
      }
      if (syms.isEmpty() && p.id().equals("finnhub")) {
        continue;                                    // company-news needs a symbol
      }
      boolean secondarySource = i > 0;
      // a secondary source fills in for a failed or empty primary, and is merged into symbol news
      if (secondarySource && primaryAnswered && (syms.isEmpty() || syms.size() > MERGE_MAX_SYMBOLS)) {
        continue;
      }
      Key key = new Key(p.id(), syms, limit, beforeKey);
      Cache<Key, List<NewsItem>> cache = secondarySource ? secondary : primary;
      List<NewsItem> items = cache.getIfPresent(key);
      if (items == null) {
        try {
          items = List.copyOf(p.news(syms, limit, before));
          cache.put(key, items);
        } catch (ProviderException | UnsupportedOperationException e) {
          log.debug("news from {} failed: {}", p.id(), e.getMessage());
          continue;
        }
      }
      if (!secondarySource && !items.isEmpty()) {
        primaryAnswered = true;
      }
      all.addAll(items);
    }
    return merge(all, limit);
  }

  /** Deduplicates by URL and by normalized headline (first source wins), newest first, at most {@code limit}. */
  static List<NewsItem> merge(List<NewsItem> items, int limit) {
    Set<String> urls = new HashSet<>();
    Set<String> headlines = new HashSet<>();
    List<NewsItem> out = new ArrayList<>();
    for (NewsItem n : items) {
      String url = n.url() == null ? "" : n.url().trim().toLowerCase(Locale.ROOT);
      String headline = normalize(n.headline());
      if (!url.isEmpty() && !urls.add(url) || !headline.isEmpty() && !headlines.add(headline)) {
        continue;
      }
      out.add(n);
    }
    out.sort(Comparator.comparing(NewsItem::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())));
    return out.size() > limit ? List.copyOf(out.subList(0, Math.max(0, limit))) : List.copyOf(out);
  }

  static String normalize(String headline) {
    return headline == null ? "" : headline.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
  }
}
