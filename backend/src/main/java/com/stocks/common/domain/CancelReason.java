package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT). The constant names are the API values (UPPER_SNAKE).
 */
public enum CancelReason {
  USER, ADMIN, PLAYER_FROZEN, PLAYER_DISABLED, ACCOUNT_RESET, CORPORATE_ACTION,
  INSUFFICIENT_BUYING_POWER_AT_EXECUTION, DUST, REPLACED_BY_USER
}
