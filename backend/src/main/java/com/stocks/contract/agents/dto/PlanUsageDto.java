package com.stocks.contract.agents.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One Claude plan usage snapshot ({@code GET /me/bots/plan-usage}): which bot's key sent it, when, around what, and the
 * plan's 5-hour session and weekly meters (0-100, null when unknown). Tokens and cost on {@code CHECKIN_AFTER} only.
 */
public record PlanUsageDto(String botHandle, PlanUsageKind kind, PlanUsageSource source, Instant takenAt,
                           LocalDate tradeDate, String roundRef, String runRef, BigDecimal sessionPercent,
                           Instant sessionResetsAt, BigDecimal weeklyPercent, Instant weeklyResetsAt,
                           AgentRunDto.AgentRunTokensDto tokens, BigDecimal costUsd) {}
