package com.stocks.league;

import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One player's numbers for one period, before the view (metric sort, kind filter, ranks and §16.7 visibility) turns
 * it into a {@code LeaderboardRow}. Returns are fractions at scale 10.
 *
 * @param accountId      the run active at the window's end
 * @param firstOpenedAt  the player's first run opening (third tie-break, §16.5)
 * @param previousRank   the player's rank in the latest earlier {@code leaderboard_snapshot} (RETURN/ALL), or null
 */
public record LeagueEntry(PlayerRow player, long accountId, BigDecimal twr, BigDecimal pnl, BigDecimal excess,
                          BigDecimal maxDrawdown, boolean rebased, boolean partialPeriod, BigDecimal equity,
                          BigDecimal netContributions, BigDecimal flowsInPeriod, int fills, int symbolsTraded,
                          BigDecimal winRate, String topHolding, boolean apiTrader, List<BigDecimal> spark,
                          Instant firstOpenedAt, boolean eligible, String eligibilityNote, Integer previousRank) {}
