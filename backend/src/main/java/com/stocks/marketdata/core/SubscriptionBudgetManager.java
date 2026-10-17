package com.stocks.marketdata.core;

import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.LeaseHandle;
import com.stocks.contract.marketdata.LeasePriority;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stream-symbol budget (§15.5). Interest in a symbol is a reference-counted lease {@code (symbol, priority, owner)}:
 * PINNED (benchmark), ORDER_TRIGGER, ORDER_RESTING, VIEWING (STOMP quote/bars), HOLDING (positions), LIST (watchlist
 * ticks, bot quotes; {@code bot:} owners expire after 60 s unless renewed). {@link #select} picks the symbols a stream
 * may carry: the top {@code max − 2} by (best priority, lease count, most recent lease), with 60 s hysteresis — a
 * recently subscribed symbol keeps its slot unless a strictly higher priority needs it.
 */
public class SubscriptionBudgetManager {

  static final Duration HYSTERESIS = Duration.ofSeconds(60);
  static final Duration BOT_LEASE_TTL = Duration.ofSeconds(60);
  /** Finnhub's stream carries only these (§15.5). */
  public static final Set<LeasePriority> FINNHUB_PRIORITIES = EnumSet.of(LeasePriority.PINNED,
      LeasePriority.ORDER_TRIGGER, LeasePriority.ORDER_RESTING, LeasePriority.HOLDING);

  private record Lease(LeasePriority priority, long seq, Instant at, Instant expiresAt) {}

  /** A symbol's demand: best priority, lease count, most recent lease. */
  public record Demand(String symbol, LeasePriority best, int count, long latestSeq) {}

  private final TimeSource time;
  private final Map<String, Map<String, Lease>> leases = new ConcurrentHashMap<>();
  private final AtomicLong seq = new AtomicLong();

  public SubscriptionBudgetManager(TimeSource time) {
    this.time = time;
  }

  /** Idempotent per {@code (symbol, owner)}: a repeat call updates the priority and renews a TTL lease. */
  public LeaseHandle lease(String symbol, LeasePriority priority, String owner) {
    Instant now = time.now();
    Instant expires = owner != null && owner.startsWith("bot:") ? now.plus(BOT_LEASE_TTL) : null;
    Lease lease = new Lease(priority, seq.incrementAndGet(), now, expires);
    leases.computeIfAbsent(symbol, k -> new ConcurrentHashMap<>()).put(owner, lease);
    return () -> release(symbol, owner, lease);
  }

  private void release(String symbol, String owner, Lease lease) {
    leases.computeIfPresent(symbol, (k, owners) -> {
      owners.remove(owner, lease);
      return owners.isEmpty() ? null : owners;
    });
  }

  public void release(String symbol, String owner) {
    leases.computeIfPresent(symbol, (k, owners) -> {
      owners.remove(owner);
      return owners.isEmpty() ? null : owners;
    });
  }

  public void releaseOwner(String owner) {
    for (String symbol : List.copyOf(leases.keySet())) {
      release(symbol, owner);
    }
  }

  /** Owners currently holding a lease on {@code symbol}. */
  public Set<String> owners(String symbol) {
    Map<String, Lease> m = leases.get(symbol);
    return m == null ? Set.of() : Set.copyOf(m.keySet());
  }

  /** Every leased symbol's demand (expired TTL leases are dropped first). */
  public List<Demand> demand() {
    Instant now = time.now();
    List<Demand> out = new ArrayList<>();
    for (Map.Entry<String, Map<String, Lease>> e : leases.entrySet()) {
      e.getValue().entrySet().removeIf(l -> l.getValue().expiresAt() != null && !now.isBefore(l.getValue().expiresAt()));
      LeasePriority best = null;
      long latest = 0;
      int count = 0;
      for (Lease l : e.getValue().values()) {
        count++;
        latest = Math.max(latest, l.seq());
        if (best == null || l.priority().ordinal() < best.ordinal()) {
          best = l.priority();
        }
      }
      if (count > 0) {
        out.add(new Demand(e.getKey(), best, count, latest));
      }
    }
    leases.entrySet().removeIf(e -> e.getValue().isEmpty());
    out.sort(ORDER);
    return out;
  }

  public Set<String> leasedSymbols() {
    Set<String> out = new LinkedHashSet<>();
    demand().forEach(d -> out.add(d.symbol()));
    return out;
  }

  /** Symbols whose best lease is one of {@code priorities}. */
  public Set<String> leasedAt(Set<LeasePriority> priorities) {
    Set<String> out = new LinkedHashSet<>();
    for (Demand d : demand()) {
      if (priorities.contains(d.best())) {
        out.add(d.symbol());
      }
    }
    return out;
  }

  public Map<String, LeasePriority> bestPriorities() {
    Map<String, LeasePriority> out = new HashMap<>();
    demand().forEach(d -> out.put(d.symbol(), d.best()));
    return out;
  }

  static final Comparator<Demand> ORDER = Comparator.comparingInt((Demand d) -> d.best().ordinal())
      .thenComparing(Comparator.comparingInt(Demand::count).reversed())
      .thenComparing(Comparator.comparingLong(Demand::latestSeq).reversed())
      .thenComparing(Demand::symbol);

  /**
   * The symbols a stream with {@code maxSymbols} slots should carry: {@code maxSymbols − 2} (headroom for the
   * unsubscribe-before-subscribe order), highest demand first, restricted to {@code allowed} priorities (null = any).
   * {@code subscribedSince} gives when each current symbol was subscribed (hysteresis).
   */
  public List<String> select(int maxSymbols, Set<LeasePriority> allowed, Map<String, Instant> subscribedSince) {
    return select(maxSymbols, allowed, subscribedSince, s -> true);
  }

  /** As {@link #select(int, Set, Map)}, among the symbols {@code eligible} accepts (a stream's asset class, §29). */
  public List<String> select(int maxSymbols, Set<LeasePriority> allowed, Map<String, Instant> subscribedSince,
                             java.util.function.Predicate<String> eligible) {
    int slots = Math.max(0, maxSymbols == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxSymbols - 2);
    Instant now = time.now();
    List<Demand> ranked = demand().stream().filter(d -> allowed == null || allowed.contains(d.best()))
        .filter(d -> eligible.test(d.symbol())).toList();
    List<Demand> chosen = new ArrayList<>();
    for (Demand d : ranked) {
      if (chosen.size() >= slots) {
        break;
      }
      chosen.add(d);
    }
    Map<String, Demand> bySymbol = new HashMap<>();
    ranked.forEach(d -> bySymbol.put(d.symbol(), d));
    Set<String> chosenSymbols = new LinkedHashSet<>();
    chosen.forEach(d -> chosenSymbols.add(d.symbol()));
    // Hysteresis: a symbol subscribed less than 60 s ago keeps its slot against equal or lower priorities.
    List<Map.Entry<String, Instant>> recent = subscribedSince.entrySet().stream()
        .filter(e -> !chosenSymbols.contains(e.getKey()) && eligible.test(e.getKey()))
        .filter(e -> Duration.between(e.getValue(), now).compareTo(HYSTERESIS) < 0)
        .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
        .toList();
    for (Map.Entry<String, Instant> e : recent) {
      Demand keeper = bySymbol.get(e.getKey());
      int keeperRank = keeper == null ? LeasePriority.values().length : keeper.best().ordinal();
      if (keeper == null && allowed != null) {
        continue;                                   // nothing in this stream's priorities wants it any more
      }
      if (chosen.size() < slots) {
        chosen.add(keeper != null ? keeper : new Demand(e.getKey(), LeasePriority.LIST, 0, 0));
        chosenSymbols.add(e.getKey());
        continue;
      }
      // replace the lowest-ranked newcomer whose priority is not strictly higher than the keeper's
      for (int i = chosen.size() - 1; i >= 0; i--) {
        Demand c = chosen.get(i);
        boolean newcomer = !subscribedSince.containsKey(c.symbol());
        if (newcomer && c.best().ordinal() >= keeperRank && keeper != null) {
          chosenSymbols.remove(c.symbol());
          chosen.set(i, keeper);
          chosenSymbols.add(keeper.symbol());
          break;
        }
      }
    }
    return List.copyOf(chosenSymbols);
  }
}
