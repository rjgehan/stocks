package com.stocks.contract.agents.dto;

/**
 * §20.8 the life of a "Run now" request: {@code PENDING} until the trader claims it ({@code CLAIMED}), then
 * {@code DONE} or {@code FAILED} as the trader reports. A request nobody claimed within 30 minutes is
 * {@code EXPIRED}; a claimed one without a report after 30 minutes is {@code FAILED}.
 */
public enum RunRequestStatus {
  PENDING, CLAIMED, DONE, FAILED, EXPIRED;

  /** PENDING or CLAIMED: the bot has an open request (at most one at a time). */
  public boolean open() {
    return this == PENDING || this == CLAIMED;
  }
}
