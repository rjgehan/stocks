package com.stocks.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.domain.AuthMethod;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.security.PlayerPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes {@code api_key.last_used_at}/{@code last_used_ip} (§17.2) and {@code player.last_seen_at} (admin Players
 * page) <b>asynchronously and at most once per minute</b> per key and per player, so authenticated requests never wait
 * on a write. Best effort: a lost update only makes "last seen" a minute older. Not governed by
 * {@code stocks.jobs.enabled} (§25).
 */
@Component
public class ActivityRecorder implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(ActivityRecorder.class);
  static final Duration MIN_INTERVAL = Duration.ofMinutes(1);

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final Cache<String, Instant> lastWritten = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(10)).maximumSize(100_000).build();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public ActivityRecorder(JdbcClient jdbc, TimeSource time) {
    this.jdbc = jdbc;
    this.time = time;
  }

  /** Records that {@code principal} made an authenticated request from {@code ip}. */
  public void seen(PlayerPrincipal principal, String ip) {
    Instant now = time.now();
    if (due("p:" + principal.playerId(), now)) {
      submit(() -> jdbc.sql("UPDATE player SET last_seen_at = ? WHERE id = ?")
          .params(Jdbc.ts(now), principal.playerId()).update());
    }
    if (principal.authMethod() == AuthMethod.API_KEY && principal.apiKeyId() != null
        && due("k:" + principal.apiKeyId(), now)) {
      submit(() -> jdbc.sql("UPDATE api_key SET last_used_at = ?, last_used_ip = ? WHERE id = ?")
          .params(Jdbc.ts(now), ip, principal.apiKeyId()).update());
    }
  }

  private boolean due(String key, Instant now) {
    boolean[] due = {false};
    lastWritten.asMap().compute(key, (k, last) -> {
      if (last == null || !now.isBefore(last.plus(MIN_INTERVAL)) || now.isBefore(last)) {
        due[0] = true;
        return now;
      }
      return last;
    });
    return due[0];
  }

  private void submit(Runnable write) {
    try {
      executor.execute(() -> {
        try {
          write.run();
        } catch (RuntimeException e) {
          log.debug("Activity write failed (best effort): {}", e.toString());
        }
      });
    } catch (RuntimeException rejected) {
      log.debug("Activity write skipped: {}", rejected.toString());
    }
  }

  @Override
  public void destroy() {
    executor.shutdownNow();
  }
}
