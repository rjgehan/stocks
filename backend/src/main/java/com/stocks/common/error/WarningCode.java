package com.stocks.common.error;

/** Warning codes (§7.2); they never block an order. */
public enum WarningCode {
  /** Order will rest until 09:30 ET. */
  QUEUED_FOR_OPEN,
  /** Order will rest until 04:00 ET. */
  QUEUED_FOR_EXT_OPEN,
  /** GTC order without extended hours will pause outside the regular session. */
  WILL_PAUSE_OUTSIDE_SESSION,
  /** Limit or stop price is more than 5% from the reference price. */
  FAT_FINGER,
  /** This sell will cause a good-faith violation. */
  GFV_RISK,
  /** The estimate uses a synthetic quote. */
  SYNTHETIC_QUOTE,
  /** The estimate uses a stale quote. */
  STALE_QUOTE,
  /** No live IEX quotes 04:00–08:00 and 17:00–20:00 ET. */
  EXT_HOURS_DATA_GAP,
  /** Quantity exceeds the displayed size. */
  PARTIAL_FILL_LIKELY,
  /** Admin transfer preview only: E⁻ is below stocks.performance.low-equity-warning (§16.2). */
  ACCOUNT_EQUITY_TOO_LOW,
  /** Admin transfer preview only: degraded marks would be used (§21.2). */
  MARKS_DEGRADED
}
