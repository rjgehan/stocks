package com.stocks.league.web;

import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.contract.portfolio.LeagueStatsQueries;
import com.stocks.contract.portfolio.dto.PlayerStatsDto;
import com.stocks.contract.portfolio.dto.RaceChartDto;
import com.stocks.contract.security.CurrentPlayer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2 league stats (§16.8, §16.9; §18.2): {@code GET /league/race} and {@code GET /players/{handle}/stats}
 * ({@code league:read}), {@code GET /account/stats} ({@code account:read}, the caller's own stats).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "league", description = "Leaderboard, players and the league feed")
public class LeagueStatsController {

  private final LeagueStatsQueries stats;

  public LeagueStatsController(LeagueStatsQueries stats) {
    this.stats = stats;
  }

  @GetMapping("/league/race")
  @Operation(summary = "The race chart", description = "Every player's unit-value curve normalized to 0% at the period "
      + "start (deposits never move a line; the last point equals the leaderboard return), ordered by RETURN rank, "
      + "with each player's EOD rank history and SPY's total return over the same window.")
  public RaceChartDto getRaceChart(
      @Parameter(description = "TODAY, WEEK (default), MONTH, YTD, SEASON or ALL")
      @RequestParam(name = "period", required = false) LeaderboardPeriod period,
      @Parameter(description = "ALL (default), HUMAN or AI")
      @RequestParam(name = "kind", required = false) KindFilter kind) {
    CurrentPlayer.require();
    return stats.race(period == null ? LeaderboardPeriod.WEEK : period, kind == null ? KindFilter.ALL : kind);
  }

  @GetMapping("/players/{handle}/stats")
  @Operation(summary = "A player's league stats", description = "Return, P&L and drawdown as on the leaderboard; "
      + "Sharpe ratio and volatility (annualized, from daily unit-value returns, at least 20 of them), win rate, "
      + "profit factor, closed lots with the average hold, best and worst trade (closed after the feed delay), rank "
      + "and the EOD rank history.")
  public PlayerStatsDto getPlayerStats(
      @PathVariable("handle") String handle,
      @Parameter(description = "TODAY, WEEK, MONTH, YTD, SEASON or ALL (default)")
      @RequestParam(name = "period", required = false) LeaderboardPeriod period) {
    return stats.playerStats(handle, period == null ? LeaderboardPeriod.ALL : period,
        CurrentPlayer.require().playerId());
  }

  @GetMapping("/account/stats")
  @Operation(summary = "Your league stats", description = "The same as /players/{handle}/stats for yourself, with "
      + "nothing delayed.")
  public PlayerStatsDto getMyStats(
      @Parameter(description = "TODAY, WEEK, MONTH, YTD, SEASON or ALL (default)")
      @RequestParam(name = "period", required = false) LeaderboardPeriod period) {
    return stats.myStats(CurrentPlayer.require().playerId(), period == null ? LeaderboardPeriod.ALL : period);
  }
}
