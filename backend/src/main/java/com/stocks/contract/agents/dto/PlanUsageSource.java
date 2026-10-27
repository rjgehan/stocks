package com.stocks.contract.agents.dto;

/**
 * Where a snapshot's percentages come from: Claude Code's {@code /usage} (no model call), or the rate-limit
 * reading that came back with the check-in itself (the fallback when {@code /usage} has no answer).
 */
public enum PlanUsageSource {
  USAGE, CHECKIN
}
