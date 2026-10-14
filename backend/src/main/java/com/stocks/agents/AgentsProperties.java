package com.stocks.agents;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * WS5's Phase 2 agent settings, {@code stocks.agents} (§20.6, §20.7, §20.9). Every key has a safe default, so
 * production needs no new configuration.
 *
 * @param maxReportBytes   the largest {@code POST /agent-runs} body (§20.6)
 * @param maxReportsPerDay new runs one bot may report in any 24 hours (§20.6; a repeated {@code runRef} is free)
 * @param journal          the AI journal limits (§20.7)
 * @param runRequests      the "Run now" requests (§20.9)
 */
@ConfigurationProperties("stocks.agents")
public record AgentsProperties(@DefaultValue("32768") int maxReportBytes, @DefaultValue("100") int maxReportsPerDay,
                               @DefaultValue Journal journal, @DefaultValue RunRequests runRequests) {

  /**
   * @param maxChars     note length after cleaning
   * @param maxPerWindow notes per {@code window} per bot
   * @param window       the sliding window of {@code maxPerWindow}
   * @param maxPerDay    notes per ET day per bot
   * @param maxSymbols   tickers per note
   */
  public record Journal(@DefaultValue("500") int maxChars, @DefaultValue("3") int maxPerWindow,
                        @DefaultValue("10m") Duration window, @DefaultValue("12") int maxPerDay,
                        @DefaultValue("5") int maxSymbols) {}

  /**
   * @param maxPerDay  requests per bot in any 24 hours (0 = no limit)
   * @param pendingTtl a request nobody claimed in this time is EXPIRED
   * @param claimTtl   a claimed request without a report in this time is FAILED
   */
  public record RunRequests(@DefaultValue("20") int maxPerDay, @DefaultValue("30m") Duration pendingTtl,
                            @DefaultValue("30m") Duration claimTtl) {}

  /** The defaults, for tests and components built without Spring. */
  public static AgentsProperties defaults() {
    return new AgentsProperties(32_768, 100, new Journal(500, 3, Duration.ofMinutes(10), 12, 5),
        new RunRequests(20, Duration.ofMinutes(30), Duration.ofMinutes(30)));
  }
}
