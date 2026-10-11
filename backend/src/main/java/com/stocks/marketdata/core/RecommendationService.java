package com.stocks.marketdata.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.Recommendations;
import com.stocks.contract.marketdata.model.RecommendationTrend;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Analyst recommendation trends (§15.10): Finnhub's free {@code /stock/recommendation} (verified free on 2026-10-10;
 * price targets are premium and not used), or the SIM's simulated ones. The upstream is the shared Finnhub budget
 * (55/min), so every answer is cached {@link #TTL} (also an empty one: an ETF or an uncovered name costs one call a
 * day), a failure {@link #FAILURE_TTL}, and a 401/403 (endpoint outside the plan) turns the upstream off for
 * {@link #FORBIDDEN_TTL}. Without a source (no {@code FINNHUB_TOKEN}) the answer is always empty and the UI hides the
 * card.
 */
public class RecommendationService {

  private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
  static final Duration TTL = Duration.ofHours(12);
  static final Duration FAILURE_TTL = Duration.ofMinutes(10);
  static final Duration FORBIDDEN_TTL = Duration.ofHours(24);
  static final int MAX_PERIODS = 6;

  private final MarketDataProvider provider;
  private final TimeSource time;
  private final LongSupplier nanos;
  private final Cache<String, Optional<Recommendations>> cache;
  private final Cache<String, Boolean> failures;
  private volatile long offUntilNanos;
  private volatile boolean off;

  /** {@code providerOrNull}: Finnhub or the SIM; null = no source (always empty). */
  public RecommendationService(MarketDataProvider providerOrNull, TimeSource time) {
    this(providerOrNull, time, System::nanoTime);
  }

  RecommendationService(MarketDataProvider providerOrNull, TimeSource time, LongSupplier nanos) {
    this.provider = providerOrNull;
    this.time = time;
    this.nanos = nanos;
    this.cache = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(3_000).ticker(nanos::getAsLong).build();
    this.failures = Caffeine.newBuilder().expireAfterWrite(FAILURE_TTL).maximumSize(3_000)
        .ticker(nanos::getAsLong).build();
  }

  public boolean available() {
    return provider != null && !(off && nanos.getAsLong() - offUntilNanos < 0);
  }

  public Optional<Recommendations> recommendations(String symbol) {
    if (symbol == null || symbol.isBlank() || !available() || failures.getIfPresent(symbol) != null) {
      return Optional.empty();
    }
    Optional<Recommendations> cached = cache.getIfPresent(symbol);
    if (cached != null) {
      return cached;
    }
    try {
      List<RecommendationTrend> trends = provider.recommendations(symbol).stream()
          .filter(t -> t.period() != null && t.total() > 0)
          .sorted(Comparator.comparing(RecommendationTrend::period).reversed())
          .limit(MAX_PERIODS).toList();
      Optional<Recommendations> out = trends.isEmpty() ? Optional.empty()
          : Optional.of(new Recommendations(symbol, provider.id(), time.now(), trends));
      cache.put(symbol, out);
      return out;
    } catch (ProviderException e) {
      if (e.status() == 401 || e.status() == 403) {
        off = true;
        offUntilNanos = nanos.getAsLong() + FORBIDDEN_TTL.toNanos();
        log.warn("Recommendations: {} refused ({}); off for {}", provider.id(), e.status(), FORBIDDEN_TTL);
      } else if (!e.throttled()) {
        log.debug("Recommendations {} failed: {}", symbol, e.getMessage());
      }
      failures.put(symbol, Boolean.TRUE);
      return Optional.empty();
    } catch (UnsupportedOperationException e) {
      cache.put(symbol, Optional.empty());
      return Optional.empty();
    }
  }
}
