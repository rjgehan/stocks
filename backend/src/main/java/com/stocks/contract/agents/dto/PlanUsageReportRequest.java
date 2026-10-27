package com.stocks.contract.agents.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code POST /agent-runs/plan-usage}: one reading of the owner's Claude plan meters, sent by the claude-trader with a
 * bot's own key. Percentages are 0-100 (one decimal kept); either may be null when the plan did not report it, but not
 * both. Tokens and cost only on {@code CHECKIN_AFTER} (the check-in's own usage).
 */
public record PlanUsageReportRequest(
    @Schema(requiredMode = RequiredMode.REQUIRED) PlanUsageKind kind,
    @Schema(requiredMode = RequiredMode.REQUIRED) PlanUsageSource source,
    @Schema(requiredMode = RequiredMode.REQUIRED) Instant takenAt,
    @Schema(description = "The trader's id of the round, ^[A-Za-z0-9._:-]{8,64}$") String roundRef,
    @Schema(description = "The trader's id of the check-in (CHECKIN_* only), ^[A-Za-z0-9._:-]{8,64}$") String runRef,
    @Schema(description = "5-hour session meter, 0-100") BigDecimal sessionPercent,
    Instant sessionResetsAt,
    @Schema(description = "Weekly (all models) meter, 0-100") BigDecimal weeklyPercent,
    Instant weeklyResetsAt,
    @Schema(description = "CHECKIN_AFTER: the check-in's tokens") AgentRunReportRequest.AgentRunTokensRequest tokens,
    @Schema(description = "CHECKIN_AFTER: Claude Code's API-price estimate in USD (not billed on a subscription)")
    BigDecimal costUsd) {}
