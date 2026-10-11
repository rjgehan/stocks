package com.stocks.contract.agents.dto;

import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** §20.6 {@code AgentRun}: one reported run (admin views). {@code tradeDate} is the ET date of {@code startedAt}. */
public record AgentRunDto(String runId, PlayerSummaryDto player, String runRef, AgentRunTrigger trigger, String slot,
                          String model, AgentRunStatus status, Instant startedAt, Instant endedAt, LocalDate tradeDate,
                          long durationMs, int turns, int toolCalls, int toolErrors, int orders,
                          AgentRunTokensDto tokens, BigDecimal costUsd, BigDecimal daySpentUsd,
                          BigDecimal dailyCapUsd, String message, String runnerVersion,
                          List<AgentToolCountDto> tools, Instant receivedAt) {

  public record AgentRunTokensDto(long input, long cacheRead, long cacheWrite, long output) {}

  /** {@code calls} calls of {@code tool} that ended with {@code code} ({@code OK} or the error code). */
  public record AgentToolCountDto(String tool, String code, int calls) {}
}
