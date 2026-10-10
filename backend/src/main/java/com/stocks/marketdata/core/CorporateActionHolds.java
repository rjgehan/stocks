package com.stocks.marketdata.core;

import com.stocks.common.time.TimeSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Symbols under a corporate-action hold (§14: a split awaiting the admin), each with the earliest ex-date of its
 * unresolved splits, read from WS3's {@code CorporateActionQueries.holds()} and cached for 10 s. When the call fails
 * (the database is unreachable, for example), no symbol is on hold until the next successful read.
 * {@link #invalidate()} (an applied corporate action or an admin alert, {@code CorporateActionCacheListener}) drops the
 * cache at once, so a resolved split's doubled position is never valued at the held, unadjusted mark.
 */
public class CorporateActionHolds {

  private static final Logger log = LoggerFactory.getLogger(CorporateActionHolds.class);
  static final Duration TTL = Duration.ofSeconds(10);

  private final Supplier<Map<String, LocalDate>> source;
  private final TimeSource time;
  private volatile Map<String, LocalDate> cached = Map.of();
  private volatile Instant loadedAt;
  private volatile boolean warned;
  private final AtomicLong generation = new AtomicLong();

  public CorporateActionHolds(Supplier<Map<String, LocalDate>> source, TimeSource time) {
    this.source = source;
    this.time = time;
  }

  /** Nothing is ever on hold (tests, tools). */
  public static CorporateActionHolds none(TimeSource time) {
    return new CorporateActionHolds(Map::of, time);
  }

  public boolean onHold(String symbol) {
    return symbol != null && holds().containsKey(symbol);
  }

  /** The earliest ex-date of the symbol's unresolved splits, or null when it is not on hold. */
  public LocalDate exDate(String symbol) {
    return symbol == null ? null : holds().get(symbol);
  }

  public Set<String> symbols() {
    return holds().keySet();
  }

  private Map<String, LocalDate> holds() {
    Instant now = time.now();
    Instant at = loadedAt;
    if (at == null || now.isBefore(at) || Duration.between(at, now).compareTo(TTL) > 0) {
      long g = generation.get();
      Map<String, LocalDate> fresh;
      try {
        Map<String, LocalDate> s = source.get();
        fresh = s == null ? Map.of() : Map.copyOf(s);
      } catch (RuntimeException e) {
        if (!warned) {
          warned = true;
          log.info("Corporate-action holds unavailable ({}); assuming none", e.getMessage());
        }
        fresh = Map.of();
      }
      if (generation.get() != g) {
        return fresh;                              // invalidated while reading: use it, but do not cache it
      }
      cached = fresh;
      loadedAt = now;
    }
    return cached;
  }

  /** Drops the cache; the next read asks {@code holds()} again. */
  public void invalidate() {
    generation.incrementAndGet();
    loadedAt = null;
  }
}
