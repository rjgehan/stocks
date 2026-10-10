package com.stocks.apilog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code stocks.api-log} (§17.10, WS5). Every key has a safe default, so production needs no new configuration.
 *
 * @param enabled            false records nothing (the views then return empty pages)
 * @param flushInterval      how often the pending hourly buckets are written
 * @param maxPendingBuckets  buckets waiting in memory at most; calls for a new bucket beyond that are dropped
 * @param retentionDays      {@code HousekeepingJob} deletes buckets older than this
 */
@ConfigurationProperties("stocks.api-log")
public record ApiAccessLogProperties(@DefaultValue("true") boolean enabled,
                                     @DefaultValue("15s") Duration flushInterval,
                                     @DefaultValue("10000") int maxPendingBuckets,
                                     @DefaultValue("90") int retentionDays) {

  public static ApiAccessLogProperties defaults() {
    return new ApiAccessLogProperties(true, Duration.ofSeconds(15), 10_000, 90);
  }
}
