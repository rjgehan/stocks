package com.stocks.common.domain;

/**
 * Phase 2 (§7.1, §19.2): the Web Push preference groups. Every notification kind belongs to exactly one category;
 * {@link #LEAGUE} covers league events that are not notifications (a new weekly leader).
 */
public enum PushCategory {
  /** FILL, PARTIAL_FILL, ORDER_CANCELLED, ORDER_EXPIRED. */
  FILLS,
  /** PRICE_ALERT. */
  ALERTS,
  /** DEPOSIT, WITHDRAWAL, ADJUSTMENT: the admin's cash movements. */
  MONEY,
  /** GFV_ISSUED, RESTRICTION_CHANGED, CORPORATE_ACTION, ACCOUNT_RESET, SYSTEM. */
  ACCOUNT,
  /** League events, e.g. a new weekly leader. */
  LEAGUE;

  /** The category of a notification kind. */
  public static PushCategory of(NotificationKind kind) {
    return switch (kind) {
      case FILL, PARTIAL_FILL, ORDER_CANCELLED, ORDER_EXPIRED -> FILLS;
      case PRICE_ALERT -> ALERTS;
      case DEPOSIT, WITHDRAWAL, ADJUSTMENT -> MONEY;
      case GFV_ISSUED, RESTRICTION_CHANGED, CORPORATE_ACTION, ACCOUNT_RESET, SYSTEM -> ACCOUNT;
    };
  }
}
