package com.stocks.contract.portfolio;

import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.contract.portfolio.dto.PlayerStatsDto;
import com.stocks.contract.portfolio.dto.RaceChartDto;

/**
 * Phase 2 league stats (§16.8, §16.9, WS4): per-player stats and the race chart, read from the live leaderboard's
 * cached computation, so they always agree with {@link LeaderboardService}.
 */
public interface LeagueStatsQueries {

  /**
   * A listed player's stats ({@code PLAYER_NOT_FOUND}; {@code ACCOUNT_NOT_FOUND} without an active run). Unless the
   * viewer is that player, best and worst trades only consider lots closed before the feed delay (§16.7).
   */
  PlayerStatsDto playerStats(String handle, LeaderboardPeriod period, Long viewerPlayerId);

  /** The caller's own stats, nothing delayed ({@code ACCOUNT_NOT_FOUND} without an active run). */
  PlayerStatsDto myStats(long playerId, LeaderboardPeriod period);

  /** Everyone's normalized unit-value curve for the period and kind filter, with SPY (§16.9). */
  RaceChartDto race(LeaderboardPeriod period, KindFilter kind);
}
