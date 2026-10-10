package com.stocks.marketdata.core;

import com.stocks.contract.marketdata.RefreshPriority;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * A provider's REST budget (§15.5): a token bucket of {@code perMinute} calls refilled continuously (Alpaca 180/min,
 * Finnhub 55/min plus 30/s), with priority classes {@code ORDER_CRITICAL > ENGINE > UI > BACKGROUND}:
 * {@code BACKGROUND} calls are deferred while less than 20% of the budget remains, so the engine and the UI keep
 * headroom; {@code ORDER_CRITICAL} and {@code ENGINE} calls may wait briefly for a token. The bucket is corrected
 * from the provider's {@code X-RateLimit-Remaining}, and an HTTP 429 blocks every call until
 * {@code X-RateLimit-Reset}. Durations use a monotonic nano clock (injectable for tests).
 */
public class ProviderRateLimiter {

  static final double BACKGROUND_RESERVE = 0.20;
  static final Duration CRITICAL_WAIT = Duration.ofMillis(1500);

  private final String provider;
  private final int perMinute;
  private final int perSecond;
  private final LongSupplier nanos;
  private double tokens;
  private double secondTokens;
  private long lastRefill;
  private long blockedUntilNanos;
  private final Deque<Long> calls = new ArrayDeque<>();

  public ProviderRateLimiter(String provider, int perMinute, int perSecond) {
    this(provider, perMinute, perSecond, System::nanoTime);
  }

  public ProviderRateLimiter(String provider, int perMinute, int perSecond, LongSupplier nanos) {
    this.provider = provider;
    this.perMinute = Math.max(1, perMinute);
    this.perSecond = Math.max(0, perSecond);
    this.nanos = nanos;
    this.tokens = this.perMinute;
    this.secondTokens = this.perSecond;
    this.lastRefill = nanos.getAsLong();
    this.blockedUntilNanos = lastRefill;
  }

  public String provider() {
    return provider;
  }

  /** Takes a permit for one call, or returns false when the budget for this priority is exhausted. */
  public boolean tryAcquire(RefreshPriority priority) {
    boolean mayWait = priority == RefreshPriority.ORDER_CRITICAL || priority == RefreshPriority.ENGINE;
    long deadline = nanos.getAsLong() + (mayWait ? CRITICAL_WAIT.toNanos() : 0);
    while (true) {
      synchronized (this) {
        refill();
        long now = nanos.getAsLong();
        boolean blocked = now - blockedUntilNanos < 0;
        double floor = priority == RefreshPriority.BACKGROUND ? perMinute * BACKGROUND_RESERVE : 0;
        if (!blocked && tokens >= 1 + floor && (perSecond == 0 || secondTokens >= 1)) {
          tokens -= 1;
          if (perSecond > 0) {
            secondTokens -= 1;
          }
          calls.addLast(now);
          return true;
        }
        if (!mayWait || now - deadline >= 0 || blocked && blockedUntilNanos - deadline > 0) {
          return false;
        }
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  /** Feeds back the provider's view: remaining calls this window, and a 429's reset delay. */
  public synchronized void onResponse(int status, Integer remainingHeader, Duration resetIn) {
    refill();
    if (remainingHeader != null && remainingHeader >= 0 && remainingHeader < tokens) {
      tokens = remainingHeader;
    }
    if (status == 429) {
      Duration wait = resetIn != null && !resetIn.isNegative() ? resetIn : Duration.ofSeconds(60);
      blockedUntilNanos = nanos.getAsLong() + wait.toNanos();
      tokens = 0;
    }
  }

  /** Calls made in the last 60 s (for {@code /admin/system}). */
  public synchronized int usedLastMinute() {
    long cutoff = nanos.getAsLong() - Duration.ofMinutes(1).toNanos();
    while (!calls.isEmpty() && calls.peekFirst() - cutoff < 0) {
      calls.removeFirst();
    }
    return calls.size();
  }

  public int perMinute() {
    return perMinute;
  }

  public synchronized double available() {
    refill();
    return tokens;
  }

  public synchronized boolean blocked() {
    return nanos.getAsLong() - blockedUntilNanos < 0;
  }

  private void refill() {
    long now = nanos.getAsLong();
    long elapsed = now - lastRefill;
    if (elapsed <= 0) {
      return;
    }
    lastRefill = now;
    tokens = Math.min(perMinute, tokens + elapsed * perMinute / 60e9);
    if (perSecond > 0) {
      secondTokens = Math.min(perSecond, secondTokens + elapsed * perSecond / 1e9);
    }
  }
}
