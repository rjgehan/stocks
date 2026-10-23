package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT). The constant names are the API values (UPPER_SNAKE).
 */
public enum DataQuality {
  REALTIME_CONSOLIDATED, REALTIME_SINGLE_VENUE, DELAYED_15M, SYNTHETIC, SIMULATED, STALE, NONE,
  /** §30: an option quote from Alpaca's free indicative feed (derived from OPRA, not the OPRA quote itself). */
  INDICATIVE
}
