package com.stocks.common.domain;

/**
 * Phase 2 (§7.1, §19.1): what a price alert watches. {@code PRICE_*} compare the live price with a price;
 * {@code CHANGE_PCT_*} compare the change from the previous close, a fraction like {@code Quote.changePct}
 * ({@code 0.05} = +5%), with a fraction.
 */
public enum AlertCondition {
  PRICE_GTE, PRICE_LTE, CHANGE_PCT_GTE, CHANGE_PCT_LTE;

  /** True for the two price conditions. */
  public boolean isPrice() {
    return this == PRICE_GTE || this == PRICE_LTE;
  }

  /** True when the condition holds at or above the threshold. */
  public boolean isAbove() {
    return this == PRICE_GTE || this == CHANGE_PCT_GTE;
  }
}
