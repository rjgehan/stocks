package com.stocks.marketdata.core;

import com.stocks.common.time.TimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Symbols under a corporate-action hold (§14: a detected split awaiting the admin), read from WS3's
 * {@code CorporateActionQueries.symbolsOnHold()} and cached for 10 s. When the call fails (the database is
 * unreachable, for example), no symbol is on hold until the next successful read. {@link #invalidate()} (an applied
 * corporate action or an admin alert, {@code CorporateActionCacheListener}) drops the cache at once, so a resolved
 * split's doubled position is never valued at the held, unadjusted mark.
 */
public class CorporateActionHolds {

  private static final Logger log = LoggerFactory.getLogger(CorporateActionHolds.class);
  static final Duration TTL = Duration.ofSeconds(10);

  private final Supplier<Collection<String>> source;
  private final TimeSource time;
  private volatile Set<String> cached = Set.of();
  private volatile Instant loadedAt;
  private volatile boolean warned;
  private final AtomicLong generation = new AtomicLong();

  public CorporateActionHolds(Supplier<Collection<String>> source, TimeSource time) {
    this.source = source;
    this.time = time;
  }

  /** Nothing is ever on hold (tests, tools). */
  public static CorporateActionHolds none(TimeSource time) {
    return new CorporateActionHolds(List::of, time);
  }

  public boolean onHold(String symbol) {
    return symbol != null && symbols().contains(symbol);
  }

  public Set<String> symbols() {
    Instant now = time.now();
    Instant at = loadedAt;
    if (at == null || now.isBefore(at) || Duration.between(at, now).compareTo(TTL) > 0) {
      long g = generation.get();
      Set<String> fresh;
      try {
        Collection<String> s = source.get();
        fresh = s == null ? Set.of() : Set.copyOf(s);
      } catch (RuntimeException e) {
        if (!warned) {
          warned = true;
          log.info("Corporate-action holds unavailable ({}); assuming none", e.getMessage());
        }
        fresh = Set.of();
      }
      if (generation.get() != g) {
        return fresh;                              // invalidated while reading: use it, but do not cache it
      }
      cached = fresh;
      loadedAt = now;
    }
    return cached;
  }

  /** Drops the cache; the next read asks {@code symbolsOnHold()} again. */
  public void invalidate() {
    generation.incrementAndGet();
    loadedAt = null;
  }
}
