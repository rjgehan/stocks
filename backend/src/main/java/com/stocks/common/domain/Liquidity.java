package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT). The constant names are the API values (UPPER_SNAKE).
 */
public enum Liquidity {
  TOUCH, WALK, RESTING, PRINT, OPEN,
  /** §30: a long option settled at expiration (cash at intrinsic value, or worthless). */
  EXPIRY
}
