package com.stocks.portfolio.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * WS4's slice of the configuration, {@code stocks.performance} (§23.1, §27.3 rule 3). Defaults mirror
 * application.yml so a missing key never yields a zero.
 *
 * @param lowEquityWarning       admin transfer preview warning {@code ACCOUNT_EQUITY_TOO_LOW} below this E⁻ (§16.2);
 *                               flows are always applied
 * @param eodDelay               the EOD snapshot runs this long after {@code post_close_at} (§16.4: 20:20 ET)
 * @param intradaySampleInterval {@code IntradaySampler} spacing (§16.4)
 * @param intradayRetentionDays  {@code intraday_equity} retention (purged by WS1's housekeeping job)
 * @param benchmarkSymbol        the total-return benchmark (§16.6)
 * @param leaderboardRefresh     the live leaderboard recompute interval (§16.5)
 * @param extraBenchmarks        Phase 2 display-only benchmarks chained like the main one (§16.6; P&amp;L Analysis),
 *                               comma-separated; blank for none
 */
@ConfigurationProperties("stocks.performance")
public record PerformanceProperties(
    @DefaultValue("1.00") BigDecimal lowEquityWarning,
    @DefaultValue("20m") Duration eodDelay,
    @DefaultValue("5m") Duration intradaySampleInterval,
    @DefaultValue("14") int intradayRetentionDays,
    @DefaultValue("SPY") String benchmarkSymbol,
    @DefaultValue("5s") Duration leaderboardRefresh,
    @DefaultValue("QQQ") String extraBenchmarks) {

  /** {@link #extraBenchmarks} as upper-case symbols, blanks and duplicates dropped. */
  public java.util.List<String> extraBenchmarkSymbols() {
    if (extraBenchmarks == null || extraBenchmarks.isBlank()) {
      return java.util.List.of();
    }
    return java.util.Arrays.stream(extraBenchmarks.split(","))
        .map(s -> s.trim().toUpperCase(java.util.Locale.ROOT)).filter(s -> !s.isEmpty()).distinct().toList();
  }

  /** The application.yml defaults, for tests and components built without Spring. */
  public static PerformanceProperties defaults() {
    return new PerformanceProperties(new BigDecimal("1.00"), Duration.ofMinutes(20), Duration.ofMinutes(5), 14, "SPY",
        Duration.ofSeconds(5), "QQQ");
  }
}
