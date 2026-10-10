package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT). The constant names are the API values (UPPER_SNAKE).
 * Open = PENDING | WORKING | PARTIALLY_FILLED. REJECTED is reserved (not produced in Phase 1, §11.2).
 */
public enum OrderStatus {
  PENDING, WORKING, PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED, REPLACED, REJECTED;

  /** True for PENDING, WORKING and PARTIALLY_FILLED. */
  public boolean isOpen() {
    return this == PENDING || this == WORKING || this == PARTIALLY_FILLED;
  }

  /** True for every status that can never change again. */
  public boolean isTerminal() {
    return !isOpen();
  }
}
