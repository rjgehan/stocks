package com.stocks.contract.agents.dto;

/** The agent runner's run statuses (§20.5), upper-cased (§20.6); inputs are case-insensitive. */
public enum AgentRunStatus {
  COMPLETED, TOOL_CAP, TIME_CAP, DAILY_CAP, MAX_TOKENS, REFUSAL, ERROR, ABORTED, SKIPPED_DAILY_CAP,
  SKIPPED_NON_TRADING_DAY, SKIPPED_BUSY;

  /** A run that did not end normally and deserves the admin's attention (§20.6 {@code AdminBot.lastProblem}). */
  public boolean problem() {
    return switch (this) {
      case ERROR, REFUSAL, MAX_TOKENS, TIME_CAP, DAILY_CAP, SKIPPED_DAILY_CAP -> true;
      default -> false;
    };
  }

  /** A run that stopped before calling the model ({@code skipped_*}). */
  public boolean skipped() {
    return this == SKIPPED_DAILY_CAP || this == SKIPPED_NON_TRADING_DAY || this == SKIPPED_BUSY;
  }
}
