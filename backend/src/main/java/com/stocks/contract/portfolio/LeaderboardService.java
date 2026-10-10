package com.stocks.contract.portfolio;

import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.contract.portfolio.dto.LeaderboardDto;

/** The leaderboard (§16.5, WS4); recomputed every 5 s and cached. */
public interface LeaderboardService {

  /** {@code viewerPlayerId} (nullable) fills {@code LeaderboardDto.me}. */
  LeaderboardDto board(LeaderboardPeriod period, LeaderboardMetric metric, KindFilter kind, int limit,
                       Long viewerPlayerId);
}
