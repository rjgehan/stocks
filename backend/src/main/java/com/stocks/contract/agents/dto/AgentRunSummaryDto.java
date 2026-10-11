package com.stocks.contract.agents.dto;

import java.time.Instant;

/**
 * §20.6 {@code AgentRunSummary}: the public "Bot activity" card of a profile ({@code GET /players/{handle}/agent-runs}).
 * No cost and no message: those stay admin-only. Zeros and nulls for a player without runs.
 */
public record AgentRunSummaryDto(String handle, int runs, int runsLast7Days, Instant lastRunAt,
                                 AgentRunStatus lastStatus, Integer lastRunOrders, Integer lastRunToolCalls) {}
