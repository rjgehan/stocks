package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT). The constant names are the API values (UPPER_SNAKE).
 */
public enum CancelReason {
  USER, ADMIN, PLAYER_FROZEN, PLAYER_DISABLED, ACCOUNT_RESET, CORPORATE_ACTION,
  INSUFFICIENT_BUYING_POWER_AT_EXECUTION, DUST, REPLACED_BY_USER,
  /** Phase 2 (§11.9): the other leg of its OCO pair executed. */
  OCO_FILLED,
  /** Phase 2 (§11.9): a held child whose entry ended without any fill. */
  PARENT_NOT_FILLED
}
