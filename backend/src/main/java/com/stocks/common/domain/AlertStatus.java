package com.stocks.common.domain;

/** Phase 2 (§7.1, §19.1): a price alert's lifecycle. Only ACTIVE alerts are evaluated. */
public enum AlertStatus {
  ACTIVE, PAUSED, TRIGGERED, EXPIRED;

  /** ACTIVE and PAUSED alerts count toward the per-player limit. */
  public boolean isLive() {
    return this == ACTIVE || this == PAUSED;
  }
}
