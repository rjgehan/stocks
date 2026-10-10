package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT). The constant names are the API values (UPPER_SNAKE).
 * §18.3 {@code Activity.type}.
 */
public enum ActivityType {
  FILL, INITIAL_FUNDING, DEPOSIT, WITHDRAWAL, ADJUSTMENT, DIVIDEND, CASH_IN_LIEU,
  SPLIT, NAME_CHANGE, GFV, RESET, RESTRICTION, ADMIN_VIEW
}
