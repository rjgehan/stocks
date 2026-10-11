package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT). The constant names are the API values (UPPER_SNAKE).
 * Open = PENDING | WORKING | PARTIALLY_FILLED | PENDING_CANCEL. REJECTED is reserved (not produced in Phase 1, §11.2).
 * {@code PENDING_CANCEL} (Phase 2, §11.10) is an API status only: a working order whose user cancel waits out the
 * cancel latency; it is never stored (the row stays WORKING or PARTIALLY_FILLED).
 */
public enum OrderStatus {
  PENDING, WORKING, PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED, REPLACED, REJECTED, PENDING_CANCEL;

  /** True for PENDING, WORKING, PARTIALLY_FILLED and PENDING_CANCEL. */
  public boolean isOpen() {
    return this == PENDING || this == WORKING || this == PARTIALLY_FILLED || this == PENDING_CANCEL;
  }

  /** True for every status that can never change again. */
  public boolean isTerminal() {
    return !isOpen();
  }
}
