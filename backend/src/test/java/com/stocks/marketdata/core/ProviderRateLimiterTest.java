package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.contract.marketdata.RefreshPriority;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** §15.5 REST budget: per-minute bucket, BACKGROUND reserve, header correction, 429 block until reset. */
class ProviderRateLimiterTest {

  final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  final ProviderRateLimiter limiter = new ProviderRateLimiter("alpaca", 10, 0, nanos::get);

  @Test
  void backgroundKeepsTwentyPercentInReserve() {
    int background = 0;
    while (limiter.tryAcquire(RefreshPriority.BACKGROUND)) {
      background++;
    }
    assertThat(background).isEqualTo(8);
    assertThat(limiter.tryAcquire(RefreshPriority.UI)).isTrue();
    assertThat(limiter.tryAcquire(RefreshPriority.UI)).isTrue();
    assertThat(limiter.tryAcquire(RefreshPriority.UI)).isFalse();
    assertThat(limiter.usedLastMinute()).isEqualTo(10);
    nanos.addAndGet(Duration.ofSeconds(6).toNanos());          // 10/min → one token per 6 s
    assertThat(limiter.tryAcquire(RefreshPriority.UI)).isTrue();
    nanos.addAndGet(Duration.ofSeconds(61).toNanos());
    assertThat(limiter.usedLastMinute()).isZero();
  }

  @Test
  void remainingHeaderLowersTheBucket() {
    limiter.onResponse(200, 1, null);
    assertThat(limiter.tryAcquire(RefreshPriority.UI)).isTrue();
    assertThat(limiter.tryAcquire(RefreshPriority.UI)).isFalse();
  }

  @Test
  void http429BlocksUntilReset() {
    limiter.onResponse(429, null, Duration.ofSeconds(30));
    assertThat(limiter.blocked()).isTrue();
    assertThat(limiter.tryAcquire(RefreshPriority.UI)).isFalse();
    nanos.addAndGet(Duration.ofSeconds(31).toNanos());
    assertThat(limiter.blocked()).isFalse();
    assertThat(limiter.tryAcquire(RefreshPriority.UI)).isTrue();
  }

  @Test
  void perSecondCap() {
    ProviderRateLimiter finnhub = new ProviderRateLimiter("finnhub", 55, 2, nanos::get);
    assertThat(finnhub.tryAcquire(RefreshPriority.UI)).isTrue();
    assertThat(finnhub.tryAcquire(RefreshPriority.UI)).isTrue();
    assertThat(finnhub.tryAcquire(RefreshPriority.UI)).isFalse();
    nanos.addAndGet(Duration.ofMillis(600).toNanos());
    assertThat(finnhub.tryAcquire(RefreshPriority.UI)).isTrue();
  }
}
