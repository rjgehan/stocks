package com.stocks.league.web;

import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.error.ApiException;
import com.stocks.contract.portfolio.LeaderboardService;
import com.stocks.contract.portfolio.dto.LeaderboardDto;
import com.stocks.contract.security.CurrentPlayer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §18.2 {@code GET /leaderboard} ({@code league:read}). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "league", description = "Leaderboard, players and the league feed")
public class LeaderboardController {

  private final LeaderboardService leaderboard;

  public LeaderboardController(LeaderboardService leaderboard) {
    this.leaderboard = leaderboard;
  }

  @GetMapping("/leaderboard")
  @Operation(summary = "The deposit-aware leaderboard", description = "RETURN ranks by time-weighted return from "
      + "unit values (admin deposits never move it; a reset never erases a loss); PNL by net-contribution P&L "
      + "(equity − net deposits), which favours larger accounts. Ties: P&L, then first opening. Rows that miss the "
      + "minimum fills/symbols follow unranked. `me` is the caller's row. Recomputed every 5 s.")
  public LeaderboardDto getLeaderboard(
      @Parameter(description = "TODAY, WEEK (default), MONTH, YTD, SEASON or ALL")
      @RequestParam(name = "period", required = false) LeaderboardPeriod period,
      @Parameter(description = "RETURN (default) or PNL")
      @RequestParam(name = "metric", required = false) LeaderboardMetric metric,
      @Parameter(description = "ALL (default), HUMAN or AI")
      @RequestParam(name = "kind", required = false) KindFilter kind,
      @Parameter(description = "1..100, default 50")
      @RequestParam(name = "limit", required = false) Integer limit) {
    if (limit != null && (limit < 1 || limit > 100)) {
      throw ApiException.validation("limit", "limit must be between 1 and 100");
    }
    return leaderboard.board(period == null ? LeaderboardPeriod.WEEK : period,
        metric == null ? LeaderboardMetric.RETURN : metric, kind == null ? KindFilter.ALL : kind,
        limit == null ? 50 : limit, CurrentPlayer.require().playerId());
  }
}
