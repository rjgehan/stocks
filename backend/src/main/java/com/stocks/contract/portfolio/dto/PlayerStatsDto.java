package com.stocks.contract.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * §18.3 {@code PlayerStats} (§16.8, Phase 2; {@code GET /players/{handle}/stats}, {@code GET /account/stats}, MCP
 * {@code get_player_stats} / {@code get_my_stats}): a player's league stats for one leaderboard period, from the same
 * anchors, EOD series and end values as the board, so {@code return} and {@code pnl} equal the player's
 * {@code LeaderboardRow}. {@code sharpe} and {@code volatility} are null below {@code sharpeMinDays} daily returns;
 * {@code profitFactor} is null without a losing sell; {@code bestTrade} and {@code worstTrade} only consider lots
 * closed at or before {@code tradesAsOf} (another player's trades appear after the feed delay, §16.7).
 */
public record PlayerStatsDto(PlayerSummaryDto player, LeaderboardPeriod period, Instant periodStart, Instant asOf,
                             @JsonProperty("return") BigDecimal ret, BigDecimal pnl, BigDecimal maxDrawdown,
                             boolean partialPeriod, boolean rebased, BigDecimal sharpe, BigDecimal volatility,
                             int dailyReturns, int sharpeMinDays, int closingSells, int wins, BigDecimal winRate,
                             BigDecimal grossProfit, BigDecimal grossLoss, BigDecimal profitFactor, int closedLots,
                             Long avgHoldSeconds, Instant tradesAsOf, ClosedLotDto bestTrade, ClosedLotDto worstTrade,
                             Integer rank, Integer rankChange, List<RankPointDto> rankHistory) {

  /**
   * One closed lot (§16.8): a buy lot relieved by one sell. {@code realizedPnl = proceeds − cost}; {@code return} is
   * {@code realizedPnl / cost} (null for a zero cost).
   */
  public record ClosedLotDto(String symbol, BigDecimal qty, Instant openedAt, Instant closedAt, long holdSeconds,
                             BigDecimal cost, BigDecimal proceeds, BigDecimal realizedPnl,
                             @JsonProperty("return") BigDecimal ret) {}

  /** The player's RETURN rank in one EOD {@code leaderboard_snapshot} ({@code ranked} rows were ranked that day). */
  public record RankPointDto(LocalDate date, Integer rank, int ranked) {}
}
