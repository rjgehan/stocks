package com.stocks.contract.jobs;

/** What {@code CatchUpRunner} replays for time the app was down (§25). */
public enum CatchUpPolicy {
  /** Every missed occurrence, in time order. */
  ALL_MISSED,
  /** Only the most recent missed occurrence. */
  LATEST_ONLY,
  /** Skip missed occurrences. */
  NONE
}
