package com.stocks.oauth;

import com.stocks.common.time.TimeSource;
import com.stocks.contract.jobs.PeriodicTask;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hourly OAuth housekeeping (§17.9.2, §17.9.6): pending requests and unexchanged codes an hour after they expired,
 * refresh tokens a day after they expired, grants revoked or without a live refresh token for 30 days, DCR clients
 * that never completed a grant after {@code stocks.oauth.dcr.unused-client-ttl} (24 h) and clients unused for
 * {@code stocks.oauth.dcr.idle-client-ttl} (90 days) with no live grant. Audit rows stay (append-only).
 */
@Component
public class OAuthCleanupTask implements PeriodicTask {

  private static final Logger log = LoggerFactory.getLogger(OAuthCleanupTask.class);
  static final Duration GRANT_RETENTION = Duration.ofDays(30);

  private final OAuthStore store;
  private final TimeSource time;
  private final OAuthProperties properties;

  public OAuthCleanupTask(OAuthStore store, TimeSource time, OAuthProperties properties) {
    this.store = store;
    this.time = time;
    this.properties = properties;
  }

  @Override
  public String name() {
    return "oauth-cleanup";
  }

  @Override
  public Duration interval() {
    return Duration.ofHours(1);
  }

  @Override
  public boolean sessionsOnly() {
    return false;
  }

  @Override
  public void tick() {
    Instant now = time.now();
    int[] n = store.cleanup(now, now.minus(Duration.ofHours(1)), now.minus(Duration.ofDays(1)),
        now.minus(GRANT_RETENTION), now.minus(properties.dcr().unusedClientTtl()),
        now.minus(properties.dcr().idleClientTtl()));
    if (n[0] + n[1] + n[2] + n[3] > 0) {
      log.info("oauth cleanup: {} requests, {} refresh tokens, {} grants, {} DCR clients deleted", n[0], n[1], n[2],
          n[3]);
    }
  }
}
