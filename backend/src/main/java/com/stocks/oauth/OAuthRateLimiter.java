package com.stocks.oauth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.time.TimeSource;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Per-client-IP limits of the OAuth endpoints (§17.9.2, §17.9.7), in memory: {@code stocks.oauth
 * .requests-per-min-per-ip} for {@code /oauth2/authorize} and {@code /oauth2/token} (each its own window) and
 * {@code stocks.oauth.dcr.per-ip-per-hour} for {@code /oauth2/register}. Fixed windows on {@link TimeSource} time;
 * over the limit the endpoint answers 429 {@code slow_down}. The OAuth chain sits outside chain 2, so the §17.5
 * buckets do not apply here.
 */
@Component
public class OAuthRateLimiter {

  /** The limited endpoint. */
  public enum Bucket { AUTHORIZE, TOKEN, REGISTER }

  private static final class Window {
    Instant start;
    int count;
  }

  private final TimeSource time;
  private final OAuthProperties properties;
  private final Cache<String, Window> windows = Caffeine.newBuilder()
      .expireAfterAccess(Duration.ofHours(2)).maximumSize(50_000).build();

  public OAuthRateLimiter(TimeSource time, OAuthProperties properties) {
    this.time = time;
    this.properties = properties;
  }

  /**
   * Counts one request of {@code ip} against {@code bucket}.
   *
   * @throws OAuthException 429 {@code slow_down} when the window is used up
   */
  public void charge(Bucket bucket, String ip) {
    int limit = bucket == Bucket.REGISTER ? properties.dcr().perIpPerHour() : properties.requestsPerMinPerIp();
    Duration length = bucket == Bucket.REGISTER ? Duration.ofHours(1) : Duration.ofMinutes(1);
    if (limit <= 0) {
      return;
    }
    Instant now = time.now();
    Window w = windows.get(bucket + "|" + (ip == null ? "?" : ip), k -> new Window());
    synchronized (w) {
      if (w.start == null || !now.isBefore(w.start.plus(length)) || now.isBefore(w.start)) {
        w.start = now;
        w.count = 0;
      }
      if (w.count >= limit) {
        long retry = Math.max(1, Duration.between(now, w.start.plus(length)).toSeconds());
        throw new OAuthException(OAuthException.SLOW_DOWN,
            "Too many requests from this address; retry in " + retry + " s.");
      }
      w.count++;
    }
  }

  /** Forgets every window (tests). */
  public void reset() {
    windows.invalidateAll();
  }
}
