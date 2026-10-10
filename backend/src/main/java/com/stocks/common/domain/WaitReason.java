package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT). The constant names are the API values (UPPER_SNAKE).
 */
public enum WaitReason {
  LATENCY, QUEUED_FOR_OPEN, QUEUED_FOR_EXT_OPEN, SESSION_CLOSED, NOT_TRIGGERED,
  AWAITING_QUOTE, HALTED, CORPORATE_ACTION_HOLD, BEYOND_COLLAR, LIMIT_NOT_REACHED,
  /** Phase 2 (§11.9): a group child held until its entry executes, or with nothing left to work until it fills more. */
  AWAITING_PARENT
}
