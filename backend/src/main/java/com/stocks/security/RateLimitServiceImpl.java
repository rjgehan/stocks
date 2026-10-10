package com.stocks.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.security.RateLimitService;
import com.stocks.contract.security.RateLimitStatus;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * §17.5 rate limits: Bucket4j buckets in memory (one instance, §12.9), keyed by <b>player</b> and shared across all of
 * a player's keys and channels; before authentication, keyed by client IP.
 *
 * <table>
 *   <tr><th>Bucket</th><th>Web session</th><th>API key / MCP</th></tr>
 *   <tr><td>reads</td><td>600/min</td><td>120/min, burst 30</td></tr>
 *   <tr><td>heavy reads (bars, history, leaderboard, profiles)</td><td>120/min</td><td>30/min</td></tr>
 *   <tr><td>order writes ({@link #consumeWrite}, {@code OrderGate} only)</td><td colspan="2">20/min and 300/day,
 *       one bucket per player for every channel</td></tr>
 *   <tr><td>per IP before authentication</td><td colspan="2">login 10/min, signup 5/hour, anything else
 *       300/min</td></tr>
 * </table>
 *
 * Buckets refill greedily on {@link TimeSource} time (a {@link TimeMeter} over {@code timeSource.now()}), so the
 * fixed test clock never refills one and a test moves time to refill. Idle buckets are forgotten after a day.
 */
@Component
public class RateLimitServiceImpl implements RateLimitService {

  /** Read classes charged by {@link RateLimitFilter}. */
  public enum ReadClass { READ, HEAVY }

  /** IP buckets charged by {@link RateLimitFilter} before authentication. */
  public enum IpClass { LOGIN, SIGNUP, ANONYMOUS }

  /**
   * One consumption: {@code limit} is the bucket's per-period rate for the {@code X-RateLimit-Limit} header,
   * {@code resetSeconds} the time until the bucket is full again, {@code retryAfterSeconds} (rejections only) the
   * wait for one token.
   */
  public record Decision(boolean allowed, long limit, long remaining, long resetSeconds, long retryAfterSeconds) {}

  private record WriteBuckets(Bucket minute, Bucket day) {}

  private final StocksProperties.RateLimit limits;
  private final TimeSource time;
  private final TimeMeter meter;
  private final Cache<String, Bucket> buckets;
  private final Cache<Long, WriteBuckets> writes;

  public RateLimitServiceImpl(StocksProperties properties, TimeSource time) {
    this.limits = properties.ratelimit();
    this.time = time;
    this.meter = new TimeSourceMeter(time);
    this.buckets = Caffeine.newBuilder().expireAfterAccess(Duration.ofHours(25)).maximumSize(200_000).build();
    this.writes = Caffeine.newBuilder().expireAfterAccess(Duration.ofHours(25)).maximumSize(50_000).build();
  }

  /** True unless {@code stocks.ratelimit.enabled=false} (reads and per-IP buckets only). */
  public boolean enabled() {
    return limits.enabled();
  }

  // ------------------------------------------------------------------------------------------- contract

  /**
   * For {@code whoami} (§20.3) and other bot-facing reports: the API-key/MCP read bucket (the one bots spend) and the
   * order-write buckets; {@code resetAt} is when both minute buckets are full again.
   */
  @Override
  public RateLimitStatus remaining(long playerId) {
    Bucket reads = readBucket(playerId, true, ReadClass.READ);
    WriteBuckets w = writeBuckets(playerId);
    long resetNanos = Math.max(nanosUntilFull(reads, limits.keyReadsBurst()),
        nanosUntilFull(w.minute(), limits.writesPerMin()));
    return new RateLimitStatus(toInt(reads.getAvailableTokens()), toInt(w.minute().getAvailableTokens()),
        toInt(w.day().getAvailableTokens()), time.now().plusNanos(resetNanos));
  }

  /**
   * One order-write token from both the per-minute and the per-day bucket (§17.5; {@code OrderGate} only, once per
   * write, after the idempotency fast path).
   *
   * @throws ApiException 429 {@code RATE_LIMITED} with {@code retryAfterS}
   */
  @Override
  public void consumeWrite(long playerId) {
    WriteBuckets w = writeBuckets(playerId);
    synchronized (w) {
      ConsumptionProbe minute = w.minute().tryConsumeAndReturnRemaining(1);
      if (!minute.isConsumed()) {
        throw rateLimited("Order writes are limited to " + limits.writesPerMin() + " per minute.",
            minute.getNanosToWaitForRefill());
      }
      ConsumptionProbe day = w.day().tryConsumeAndReturnRemaining(1);
      if (!day.isConsumed()) {
        w.minute().addTokens(1);
        throw rateLimited("Order writes are limited to " + limits.writesPerDay() + " per day.",
            day.getNanosToWaitForRefill());
      }
    }
  }

  /** Gives back the token of a write the engine detected as an idempotent replay (§11.2: replays are free). */
  @Override
  public void refundWrite(long playerId) {
    WriteBuckets w = writeBuckets(playerId);
    synchronized (w) {
      w.minute().addTokens(1);
      w.day().addTokens(1);
    }
  }

  // ------------------------------------------------------------------------------------------- filter API

  /** Charges one read (or heavy read) of {@code playerId}; {@code apiKey} selects the key/MCP limits. */
  public Decision consumeRead(long playerId, boolean apiKey, ReadClass readClass) {
    Bucket bucket = readBucket(playerId, apiKey, readClass);
    long perMinute = switch (readClass) {
      case READ -> apiKey ? limits.keyReadsPerMin() : limits.webReadsPerMin();
      case HEAVY -> apiKey ? limits.heavyReadsPerMinKey() : limits.heavyReadsPerMinWeb();
    };
    return consume(bucket, perMinute, capacity(apiKey, readClass));
  }

  /** Charges one request of an unauthenticated caller from {@code ip}. */
  public Decision consumeIp(String ip, IpClass ipClass) {
    String key = "ip:" + ipClass + ":" + (ip == null ? "?" : ip);
    long rate = switch (ipClass) {
      case LOGIN -> limits.loginPerMinIp();
      case SIGNUP -> limits.signupPerHourIp();
      case ANONYMOUS -> limits.anonPerMinIp();
    };
    Duration period = ipClass == IpClass.SIGNUP ? Duration.ofHours(1) : Duration.ofMinutes(1);
    Bucket bucket = buckets.get(key, k -> bucket(rate, rate, period));
    return consume(bucket, rate, rate);
  }

  // ------------------------------------------------------------------------------------------- internals

  private Decision consume(Bucket bucket, long limit, long capacity) {
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    long reset = ceilSeconds(nanosUntilFull(bucket, capacity));
    if (probe.isConsumed()) {
      return new Decision(true, limit, probe.getRemainingTokens(), reset, 0);
    }
    return new Decision(false, limit, 0, reset, Math.max(1, ceilSeconds(probe.getNanosToWaitForRefill())));
  }

  private long capacity(boolean apiKey, ReadClass readClass) {
    return switch (readClass) {
      case READ -> apiKey ? limits.keyReadsBurst() : limits.webReadsPerMin();
      case HEAVY -> apiKey ? limits.heavyReadsPerMinKey() : limits.heavyReadsPerMinWeb();
    };
  }

  private Bucket readBucket(long playerId, boolean apiKey, ReadClass readClass) {
    String key = (readClass == ReadClass.HEAVY ? "heavy:" : "read:") + (apiKey ? "key:" : "web:") + playerId;
    long perMinute = switch (readClass) {
      case READ -> apiKey ? limits.keyReadsPerMin() : limits.webReadsPerMin();
      case HEAVY -> apiKey ? limits.heavyReadsPerMinKey() : limits.heavyReadsPerMinWeb();
    };
    return buckets.get(key, k -> bucket(capacity(apiKey, readClass), perMinute, Duration.ofMinutes(1)));
  }

  private WriteBuckets writeBuckets(long playerId) {
    return writes.get(playerId, id -> new WriteBuckets(
        bucket(limits.writesPerMin(), limits.writesPerMin(), Duration.ofMinutes(1)),
        bucket(limits.writesPerDay(), limits.writesPerDay(), Duration.ofDays(1))));
  }

  private Bucket bucket(long capacity, long refillTokens, Duration period) {
    long cap = Math.max(1, capacity);
    return Bucket.builder()
        .addLimit(Bandwidth.builder().capacity(cap).refillGreedy(Math.max(1, refillTokens), period).build())
        .withCustomTimePrecision(meter)
        .build();
  }

  private static long nanosUntilFull(Bucket bucket, long capacity) {
    long missing = Math.max(0, capacity - bucket.getAvailableTokens());
    if (missing == 0) {
      return 0;
    }
    return Math.max(0, bucket.estimateAbilityToConsume(capacity).getNanosToWaitForRefill());
  }

  private ApiException rateLimited(String detail, long nanosToWait) {
    return ApiException.of(ErrorCode.RATE_LIMITED, detail)
        .withHint("Wait retryAfterS seconds before the next order write.")
        .withRetryAfter(Math.max(1, ceilSeconds(nanosToWait)));
  }

  static long ceilSeconds(long nanos) {
    return nanos <= 0 ? 0 : (nanos + 999_999_999L) / 1_000_000_000L;
  }

  private static int toInt(long v) {
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0, v));
  }

  /** Bucket4j time from {@link TimeSource} (epoch nanoseconds), so tests and SIM clocks drive refills. */
  static final class TimeSourceMeter implements TimeMeter {
    private final TimeSource time;

    TimeSourceMeter(TimeSource time) {
      this.time = time;
    }

    @Override
    public long currentTimeNanos() {
      Instant now = time.now();
      return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    @Override
    public boolean isWallClockBased() {
      return true;
    }
  }
}
