package com.stocks.contract.agents.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * {@code POST /agent-runs} (§20.6): one run's summary, sent by the agent runner with the bot's own key. The run is
 * always recorded for the key's player; {@code runRef} makes the report idempotent.
 */
public record AgentRunReportRequest(
    @Schema(requiredMode = RequiredMode.REQUIRED, description = "The runner's id for this run, ^[A-Za-z0-9._:-]{8,64}$")
    String runRef,
    @Schema(requiredMode = RequiredMode.REQUIRED) AgentRunTrigger trigger,
    @Schema(description = "HH:MM (ET) of a scheduled run") String slot,
    @Schema(requiredMode = RequiredMode.REQUIRED) String model,
    @Schema(requiredMode = RequiredMode.REQUIRED, description = "The runner's status, case-insensitive")
    AgentRunStatus status,
    @Schema(requiredMode = RequiredMode.REQUIRED) Instant startedAt,
    @Schema(requiredMode = RequiredMode.REQUIRED) Instant endedAt,
    Integer turns,
    Integer toolCalls,
    Integer toolErrors,
    Integer orders,
    AgentRunTokensRequest tokens,
    @Schema(requiredMode = RequiredMode.REQUIRED, description = "USD, decimal string") BigDecimal costUsd,
    @Schema(requiredMode = RequiredMode.REQUIRED, description = "USD spent by the bot today (ET), this run included")
    BigDecimal daySpentUsd,
    @Schema(requiredMode = RequiredMode.REQUIRED, description = "The bot's daily cap in USD") BigDecimal dailyCapUsd,
    @Schema(description = "Why the run stopped or was skipped (at most 500 characters are kept)") String message,
    String runnerVersion,
    @Schema(description = "Tool calls by tool and result code (OK or the error code), at most 200 entries")
    List<AgentRunToolRequest> tools) {

  /** Token usage of the run, summed over its model responses. */
  public record AgentRunTokensRequest(Long input, Long cacheRead, Long cacheWrite, Long output) {}

  /** {@code calls} calls of {@code tool} that ended with {@code code}. */
  public record AgentRunToolRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED) String tool,
      @Schema(requiredMode = RequiredMode.REQUIRED) String code,
      @Schema(requiredMode = RequiredMode.REQUIRED) Integer calls) {}
}
