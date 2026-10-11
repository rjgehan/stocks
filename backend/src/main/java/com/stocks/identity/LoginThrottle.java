package com.stocks.identity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.ClientNetworks;
import com.stocks.config.StocksProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The §17.3 login lockout, for anonymous {@code POST /auth/login} only (re-authentication and password changes come
 * from an authenticated session and are never locked by it):
 * <ul>
 *   <li><b>Per (handle, client IP)</b>: {@code login-max-failures} (10) consecutive failures lock that pair for
 *       {@code login-lockout} (15 min); the 10th failure already answers {@code LOGIN_LOCKED}. A stranger can lock
 *       out only their own IP, never the player.</li>
 *   <li><b>Per handle</b>, across all IPs: {@code login-max-failures-per-handle} (100) failures within one lockout
 *       window lock the handle for {@code login-lockout}, which bounds distributed guessing.</li>
 * </ul>
 * Unknown handles are counted exactly like real ones, so the lockout reveals nothing about which handles exist. In
 * memory (one instance, D2): a restart forgets the counters. Instants come from {@link TimeSource}; Caffeine only
 * bounds the memory.
 */
@Component
public class LoginThrottle {

  /** Failures of one key within the current window, and the end of its lock (null = not locked). */
  private record Counter(int failures, Instant windowStart, Instant lockedUntil) {}

  private final TimeSource time;
  private final int maxPerPair;
  private final int maxPerHandle;
  private final Duration lockout;
  private final Cache<String, Counter> pairs = Caffeine.newBuilder().maximumSize(50_000)
      .expireAfterAccess(Duration.ofHours(2)).build();
  private final Cache<String, Counter> handles = Caffeine.newBuilder().maximumSize(50_000)
      .expireAfterAccess(Duration.ofHours(2)).build();

  public LoginThrottle(TimeSource time, StocksProperties properties) {
    this.time = time;
    this.maxPerPair = properties.security().loginMaxFailures();
    this.maxPerHandle = properties.security().loginMaxFailuresPerHandle();
    this.lockout = properties.security().loginLockout();
  }

  /** The end of the lock on this attempt, if the pair or the handle is locked now. */
  public Optional<Instant> lockedUntil(String handle, String ip) {
    Instant now = time.now();
    Instant a = active(pairs.getIfPresent(pairKey(handle, ip)), now);
    Instant b = active(handles.getIfPresent(handleKey(handle)), now);
    if (a == null) {
      return Optional.ofNullable(b);
    }
    return Optional.of(b == null || a.isAfter(b) ? a : b);
  }

  /** Counts a failed login; returns the lock's end if this failure locked the pair or the handle. */
  public Optional<Instant> failure(String handle, String ip) {
    Instant now = time.now();
    pairs.asMap().compute(pairKey(handle, ip), (k, c) -> bump(c, now, maxPerPair));
    handles.asMap().compute(handleKey(handle), (k, c) -> bump(c, now, maxPerHandle));
    return lockedUntil(handle, ip);
  }

  /** A successful login clears this pair's counter (the per-handle window keeps counting). */
  public void success(String handle, String ip) {
    pairs.invalidate(pairKey(handle, ip));
  }

  /** Clears every counter of the handle (an admin password reset, tests). */
  public void reset(String handle) {
    String h = handleKey(handle);
    handles.invalidate(h);
    pairs.asMap().keySet().removeIf(k -> k.startsWith(h + "|"));
  }

  private Counter bump(Counter c, Instant now, int max) {
    // a lock that ended, or a window older than the lockout, starts over
    if (c == null || (c.lockedUntil() != null && !c.lockedUntil().isAfter(now))
        || (c.lockedUntil() == null && !c.windowStart().plus(lockout).isAfter(now))) {
      c = new Counter(0, now, null);
    }
    int n = c.failures() + 1;
    if (n >= max) {
      return new Counter(0, now, now.plus(lockout));
    }
    return new Counter(n, c.windowStart(), c.lockedUntil());
  }

  private static Instant active(Counter c, Instant now) {
    return c != null && c.lockedUntil() != null && c.lockedUntil().isAfter(now) ? c.lockedUntil() : null;
  }

  private static String handleKey(String handle) {
    return handle == null ? "" : handle.toLowerCase(Locale.ROOT);
  }

  private static String pairKey(String handle, String ip) {
    return handleKey(handle) + "|" + (ip == null ? "" : ClientNetworks.bucketKey(ip));   // IPv6: its /64
  }
}
