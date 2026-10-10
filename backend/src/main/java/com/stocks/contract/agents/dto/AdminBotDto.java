package com.stocks.contract.agents.dto;

import com.stocks.common.domain.PlayerStatus;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * §20.6 {@code AdminBot} (Admin → Bots): one AI player's runs, spend against the runner's daily cap and tool calls.
 * {@code lastProblem} is the latest run with a problem status ({@link AgentRunStatus#problem()}).
 */
public record AdminBotDto(PlayerSummaryDto player, PlayerStatus status, AgentRunDto lastRun, AgentRunDto lastProblem,
                          BotTodayDto today, List<BotDayDto> days, List<BotToolDto> tools) {

  /** Today (ET): runs, the highest {@code daySpentUsd} reported, the cap of the latest report. */
  public record BotTodayDto(LocalDate date, int runs, BigDecimal spentUsd, BigDecimal capUsd, boolean capReached) {}

  /** One ET day of the 14-day spend chart, oldest first. */
  public record BotDayDto(LocalDate date, int runs, BigDecimal spentUsd, BigDecimal capUsd) {}

  /** One tool over the last 7 days: calls, errors (non-OK) and the calls per result code. */
  public record BotToolDto(String tool, int calls, int errors, List<BotToolCodeDto> codes) {}

  public record BotToolCodeDto(String code, int calls) {}
}
