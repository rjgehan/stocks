package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT, Phase 2, §11.9), derived from the current legs: ACTIVE while any leg is open; then COMPLETED if any
 * leg executed, else CANCELLED or EXPIRED.
 */
public enum OrderGroupStatus {
  ACTIVE, COMPLETED, CANCELLED, EXPIRED
}
