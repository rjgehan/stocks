package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT). The constant names are the API values (UPPER_SNAKE).
 */
public enum NotificationKind {
  FILL, PARTIAL_FILL, ORDER_CANCELLED, ORDER_EXPIRED, DEPOSIT, WITHDRAWAL, ADJUSTMENT,
  GFV_ISSUED, RESTRICTION_CHANGED, CORPORATE_ACTION, ACCOUNT_RESET, SYSTEM,
  /** Phase 2: a price alert fired, or alerts were paused by a corporate action (§19.1). */
  PRICE_ALERT
}
