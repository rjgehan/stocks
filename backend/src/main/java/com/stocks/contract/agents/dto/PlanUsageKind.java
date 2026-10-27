package com.stocks.contract.agents.dto;

/** When a Claude plan usage snapshot was taken: around one bot's check-in, or around a round of check-ins. */
public enum PlanUsageKind {
  CHECKIN_BEFORE, CHECKIN_AFTER, ROUND_BEFORE, ROUND_AFTER
}
