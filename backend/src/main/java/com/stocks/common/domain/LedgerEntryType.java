package com.stocks.common.domain;

/**
 * §7.1 (CONTRACT). The constant names are the API values (UPPER_SNAKE).
 */
public enum LedgerEntryType {
  INITIAL_FUNDING, DEPOSIT, WITHDRAWAL, ADJUSTMENT, TRADE_BUY, TRADE_SELL, DIVIDEND, CASH_IN_LIEU
}
