package com.stocks.common.domain;

/** §7.1 (CONTRACT, Phase 2, §11.9): the role of one leg in its {@link OrderGroupType group}. */
public enum OrderGroupRole {
  ENTRY, TAKE_PROFIT, STOP_LOSS, SECONDARY;

  /** TAKE_PROFIT and STOP_LOSS: the two legs of an OCO pair. */
  public boolean inOcoPair() {
    return this == TAKE_PROFIT || this == STOP_LOSS;
  }
}
