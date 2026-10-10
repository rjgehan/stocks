package com.stocks.contract.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.portfolio.dto.PlayerStatsDto.RankPointDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * §18.3 {@code RaceChart} (§16.9, Phase 2; {@code GET /league/race}, MCP {@code get_race_chart}): every player's
 * unit-value curve normalized to 0% at the period start ({@code U(t) / U(a) − 1}, −1 after a rebase), so deposits
 * never move a line and the last point equals the player's {@code LeaderboardRow.return}. Series follow the RETURN
 * rank of the view (unranked last); {@code benchmark} is SPY's total return over the same window.
 */
public record RaceChartDto(LeaderboardPeriod period, KindFilter kind, Instant periodStart, Instant asOf,
                           BenchmarkCurveDto benchmark, List<RaceSeriesDto> series) {

  /** One player's line: rank and return of the view, the normalized points and the EOD rank history. */
  public record RaceSeriesDto(PlayerSummaryDto player, Integer rank, @JsonProperty("return") BigDecimal ret,
                              boolean rebased, boolean partialPeriod, List<RacePointDto> points,
                              List<RankPointDto> ranks) {}

  /** One point of a normalized curve: the return since the period start at {@code t}. */
  public record RacePointDto(Instant t, @JsonProperty("return") BigDecimal ret) {}
}
