package com.stocks.trading.engine;

import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.WaitReason;
import com.stocks.contract.marketdata.LeaseHandle;
import com.stocks.trading.store.OrderRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The open orders in memory, by id and by symbol (§12.2). Rebuilt from the database at boot and changed only after
 * a commit ({@link EngineTx#afterCommit}). It also carries per-order evaluation bookkeeping that never needs to be
 * durable (last evaluation, print cursor, "event already written" flags, the market-data lease). The authoritative
 * order state is always re-read from the database inside each engine transaction.
 */
@Component
public final class OpenOrderIndex {

  /** One open order. Mutated only on the engine thread. */
  public static final class Entry {
    final long id;
    final String publicId;
    final long accountId;
    final long playerId;
    final String symbol;
    final Side side;
    final OrderType type;
    final boolean extendedHours;
    volatile OrderStatus status;
    volatile Instant eligibleAt;
    volatile Instant expiresAt;
    volatile WaitReason waitReason;
    volatile boolean hasRested;
    Instant lastEvaluatedAt;
    long printCursor;
    boolean reopenPending;
    boolean awaitingQuoteNoted;
    boolean beyondCollarNoted;
    boolean queuedForOpen;
    LeaseHandle lease;

    Entry(OrderRow r) {
      this.id = r.id();
      this.publicId = r.publicId();
      this.accountId = r.accountId();
      this.playerId = r.playerId();
      this.symbol = r.symbol();
      this.side = r.side();
      this.type = r.type();
      this.extendedHours = r.extendedHours();
      refresh(r);
      this.queuedForOpen = r.waitReason() == WaitReason.QUEUED_FOR_OPEN
          || r.waitReason() == WaitReason.QUEUED_FOR_EXT_OPEN;
    }

    void refresh(OrderRow r) {
      this.status = r.status();
      this.eligibleAt = r.eligibleAt();
      this.expiresAt = r.expiresAt();
      this.waitReason = r.waitReason();
      this.hasRested = r.hasRested();
    }

    public long id() {
      return id;
    }

    public String publicId() {
      return publicId;
    }

    public String symbol() {
      return symbol;
    }

    public long accountId() {
      return accountId;
    }

    public OrderStatus status() {
      return status;
    }

    public Instant eligibleAt() {
      return eligibleAt;
    }

    public Instant expiresAt() {
      return expiresAt;
    }

    public WaitReason waitReason() {
      return waitReason;
    }
  }

  private static final Comparator<Entry> EVALUATION_ORDER =
      Comparator.comparing((Entry e) -> e.eligibleAt).thenComparingLong(e -> e.id);

  private final Map<Long, Entry> byId = new ConcurrentHashMap<>();
  private final Map<String, Set<Long>> bySymbol = new ConcurrentHashMap<>();

  /** Adds (or refreshes) an open order; returns its entry. */
  public Entry put(OrderRow row) {
    Entry existing = byId.get(row.id());
    if (existing != null) {
      existing.refresh(row);
      return existing;
    }
    Entry e = new Entry(row);
    byId.put(row.id(), e);
    bySymbol.computeIfAbsent(row.symbol(), s -> ConcurrentHashMap.newKeySet()).add(row.id());
    return e;
  }

  public Entry get(long id) {
    return byId.get(id);
  }

  /** Removes an order that became terminal; returns its entry (for the lease), or null. */
  public Entry remove(long id) {
    Entry e = byId.remove(id);
    if (e != null) {
      Set<Long> ids = bySymbol.get(e.symbol);
      if (ids != null) {
        ids.remove(id);
        if (ids.isEmpty()) {
          bySymbol.remove(e.symbol);
        }
      }
    }
    return e;
  }

  /** Cheap check from market-data threads (§15.5: the engine ignores symbols it has no orders on). */
  public boolean hasSymbol(String symbol) {
    Set<Long> ids = bySymbol.get(symbol);
    return ids != null && !ids.isEmpty();
  }

  public Set<String> symbols() {
    return Set.copyOf(bySymbol.keySet());
  }

  /** Open orders on a symbol in evaluation order {@code (eligible_at, id)} (§12.2). */
  public List<Entry> on(String symbol) {
    List<Entry> out = new ArrayList<>();
    for (Long id : bySymbol.getOrDefault(symbol, Set.of())) {
      Entry e = byId.get(id);
      if (e != null) {
        out.add(e);
      }
    }
    out.sort(EVALUATION_ORDER);
    return out;
  }

  public Collection<Entry> all() {
    List<Entry> out = new ArrayList<>(byId.values());
    out.sort(EVALUATION_ORDER);
    return out;
  }

  public int size() {
    return byId.size();
  }

  public void clear() {
    byId.clear();
    bySymbol.clear();
  }
}
