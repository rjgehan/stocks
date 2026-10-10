package com.stocks.trading.engine;

import com.stocks.contract.marketdata.Print;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Eligible prints drained from market data ({@code drainPrints} hands each print out once), kept per symbol with a
 * sequence number so every resting order sees every print exactly once even when it is evaluated less often than
 * prints arrive (trade-through fills, §12.5). Engine thread only; prints older than {@link #RETENTION} are dropped.
 */
final class PrintBuffer {

  static final Duration RETENTION = Duration.ofMinutes(5);
  private static final int MAX_PER_SYMBOL = 2_000;

  record Seq(long seq, Print print) {}

  private final Map<String, Deque<Seq>> bySymbol = new HashMap<>();
  private long nextSeq = 1;

  void append(String symbol, List<Print> prints, Instant now) {
    Deque<Seq> q = bySymbol.computeIfAbsent(symbol, s -> new ArrayDeque<>());
    for (Print p : prints) {
      q.addLast(new Seq(nextSeq++, p));
    }
    Instant cutoff = now.minus(RETENTION);
    while (!q.isEmpty() && (q.size() > MAX_PER_SYMBOL || q.peekFirst().print().observedAt().isBefore(cutoff))) {
      q.removeFirst();
    }
  }

  /** Prints after {@code cursor} (exclusive). */
  List<Seq> since(String symbol, long cursor) {
    List<Seq> out = new ArrayList<>();
    for (Seq s : bySymbol.getOrDefault(symbol, new ArrayDeque<>())) {
      if (s.seq() > cursor) {
        out.add(s);
      }
    }
    return out;
  }

  /** The latest sequence number handed out (0 before any print). */
  long lastSeq() {
    return nextSeq - 1;
  }

  void forget(String symbol) {
    bySymbol.remove(symbol);
  }

  /** Forgets every print (a restart, §12.9). Sequence numbers keep increasing. */
  void clear() {
    bySymbol.clear();
  }
}
