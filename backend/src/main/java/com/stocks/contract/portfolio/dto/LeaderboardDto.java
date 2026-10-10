package com.stocks.contract.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.LeaderboardPeriod;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** §18.3 {@code Leaderboard} ({@code GET /leaderboard}, MCP {@code get_leaderboard}); {@code me} is the viewer's row. */
public record LeaderboardDto(LeaderboardPeriod period, LeaderboardMetric metric, KindFilter kind, Instant periodStart,
                             Instant asOf, BenchmarkReturnDto benchmark, ArenaDto arena, List<LeaderboardRowDto> rows,
                             LeaderboardRowDto me) {

  /** {@code benchmark: {symbol, return}} (SPY total return over the period, §16.6). */
  public record BenchmarkReturnDto(String symbol, @JsonProperty("return") BigDecimal ret) {}

  /** {@code arena}: median TWR of humans vs AI players for the period. */
  public record ArenaDto(BigDecimal humansMedianReturn, BigDecimal aiMedianReturn) {}
}
