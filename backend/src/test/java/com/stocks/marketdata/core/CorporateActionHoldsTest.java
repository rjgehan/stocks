package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.time.MutableTimeSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The corporate-action hold cache (§14): 10 s TTL, dropped at once by {@code invalidate()} after a resolve. */
class CorporateActionHoldsTest {

  final MutableTimeSource time = new MutableTimeSource(Instant.parse("2026-10-14T13:20:00Z"));
  final AtomicReference<Map<String, LocalDate>> table =
      new AtomicReference<>(Map.of("SIMS", LocalDate.of(2026, 10, 14)));
  final AtomicInteger reads = new AtomicInteger();

  @Test
  void cachedForTenSecondsUnlessInvalidated() {
    CorporateActionHolds holds = new CorporateActionHolds(() -> {
      reads.incrementAndGet();
      return table.get();
    }, time);
    assertThat(holds.onHold("SIMS")).isTrue();
    assertThat(holds.exDate("SIMS")).isEqualTo(LocalDate.of(2026, 10, 14));
    assertThat(holds.exDate("AAPL")).isNull();
    table.set(Map.of());                                       // the admin resolved the split
    time.advance(Duration.ofSeconds(5));
    assertThat(holds.onHold("SIMS")).isTrue();                  // still cached: the integration bug's window
    holds.invalidate();                                         // CorporateActionCacheListener, after commit
    assertThat(holds.onHold("SIMS")).isFalse();
    assertThat(reads).hasValue(2);
    time.advance(Duration.ofSeconds(11));
    assertThat(holds.symbols()).isEqualTo(Set.of());
    assertThat(reads).hasValue(3);
  }

  @Test
  void aReadThatRacedWithAnInvalidateIsNotCached() {
    AtomicReference<CorporateActionHolds> self = new AtomicReference<>();
    CorporateActionHolds holds = new CorporateActionHolds(() -> {
      if (reads.incrementAndGet() == 1) {
        self.get().invalidate();                                // the engine committed while we were reading
      }
      return table.get();
    }, time);
    self.set(holds);
    assertThat(holds.symbols()).containsExactly("SIMS");
    assertThat(holds.symbols()).containsExactly("SIMS");
    assertThat(reads).hasValue(2);                              // the raced read was used once, then re-read
    assertThat(holds.symbols()).containsExactly("SIMS");
    assertThat(reads).hasValue(2);                              // the second read was cached
  }
}
