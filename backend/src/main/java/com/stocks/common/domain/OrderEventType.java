package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT). The constant names are the API values (UPPER_SNAKE).
 */
public enum OrderEventType {
  ACCEPTED, QUEUED, WORKING, PAUSED, RESUMED, TRIGGERED, TRAIL_UPDATED, AWAITING_QUOTE,
  BEYOND_COLLAR, PARTIAL_FILL, FILL, CANCELLED, EXPIRED, REPLACED, HOLD_ADJUSTED
}
