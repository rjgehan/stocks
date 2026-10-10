package com.stocks.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The only source of "now" (§5.3, §6.4, D22). Inject it; never call {@code Instant.now()} and friends outside this
 * package (ArchUnit rule 5). Implementations return instants truncated to microseconds, the precision of
 * {@code TIMESTAMP WITH TIME ZONE} on PostgreSQL and H2, so a value read back from the DB equals the value written.
 */
public interface TimeSource {

  /** America/New_York: every market rule uses it (§9). */
  ZoneId ET = ZoneId.of("America/New_York");

  Instant now();

  /** A UTC {@link Clock} view of this source, for APIs that need one. */
  Clock clock();

  /** The ET calendar date of {@link #now()}. */
  default LocalDate todayEt() {
    return LocalDate.ofInstant(now(), ET);
  }
}
