package com.stocks.marketdata.spi;

import java.time.Instant;
import java.util.Set;

/** One upstream stream connection per provider (§15.3). Subscriptions are full desired state; the stream diffs. */
public interface MarketStream extends AutoCloseable {

  /** Idempotent; respects the provider's symbol limit (excess symbols are dropped, lowest priority last in order). */
  void setDesired(Set<String> trades, Set<String> quotes, Set<String> bars);

  ConnectionState state();

  /** Last message of any kind on the connection; null before the first one. */
  Instant lastMessageAt();

  /** Symbols whose quotes have been continuously subscribed on a live connection since at or before {@code t}. */
  Set<String> subscribedSince(Instant t);

  /** Symbols currently subscribed (confirmed by the provider). */
  Set<String> subscribed();

  /** Human-readable detail for {@code /admin/system} (last error, backoff). */
  default String detail() {
    return null;
  }

  @Override
  void close();
}
