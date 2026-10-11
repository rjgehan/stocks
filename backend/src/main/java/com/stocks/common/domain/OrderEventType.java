package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT). The constant names are the API values (UPPER_SNAKE).
 */
public enum OrderEventType {
  ACCEPTED, QUEUED, WORKING, PAUSED, RESUMED, TRIGGERED, TRAIL_UPDATED, AWAITING_QUOTE,
  BEYOND_COLLAR, PARTIAL_FILL, FILL, CANCELLED, EXPIRED, REPLACED, HOLD_ADJUSTED,
  /** Phase 2: a group child released by its entry's execution (§11.9). */
  ACTIVATED,
  /** Phase 2: a group child's working or total quantity followed its entry (§11.9). */
  QTY_ADJUSTED,
  /** Phase 2: prices reduced on an ex-dividend date (FINRA 5330, §14). */
  PRICE_ADJUSTED,
  /** Phase 2: a user cancel waiting out the cancel latency (§11.10). */
  CANCEL_REQUESTED
}
