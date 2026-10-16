package com.stocks.contract.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * §18.3 {@code LeaderboardRow} (§16.5). {@code rank} null when ineligible; {@code equity} and {@code topHolding} are
 * always shown (public mode, §16.7); {@code spark} up to 30 daily unit values plus the live one. Phase 2 (§16.8):
 * {@code sharpe} (null below 20 daily returns) and {@code profitFactor} (null without a losing sell) for the period.
 * {@code dayReturn}/{@code dayPnl} and {@code totalReturn}/{@code totalPnl} are the player's TODAY and ALL numbers,
 * whatever the period (null where the board does not carry them).
 */
public record LeaderboardRowDto(Integer rank, Integer rankChange, boolean eligible, String eligibilityNote,
                                boolean partialPeriod, boolean rebased, PlayerSummaryDto player, boolean apiTrader,
                                @JsonProperty("return") BigDecimal ret, BigDecimal pnl, BigDecimal excessVsBenchmark,
                                BigDecimal maxDrawdown, BigDecimal equity, BigDecimal netContributions,
                                BigDecimal flowsInPeriod, int fills, int symbolsTraded, BigDecimal winRate,
                                String topHolding, List<BigDecimal> spark, BigDecimal sharpe,
                                BigDecimal profitFactor, BigDecimal dayReturn, BigDecimal dayPnl,
                                BigDecimal totalReturn, BigDecimal totalPnl) {}
