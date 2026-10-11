package com.stocks.common.domain;

/** §7.1 (CONTRACT, Phase 2, §11.9). The constant names are the API values (UPPER_SNAKE). */
public enum OrderGroupType {
  /** One-cancels-other: a LIMIT leg and a STOP / STOP_LIMIT leg on one side; the first execution cancels the other. */
  OCO,
  /** One-triggers-other: an entry, and a secondary held until the entry executes. */
  OTO,
  /** An entry whose execution releases an OCO pair (take profit and stop loss). */
  OTOCO,
  /** OTOCO with the bracket price rules (take profit above the entry, stop loss below, at least 0.1% apart). */
  BRACKET;

  /** OTO, OTOCO and BRACKET have an entry whose children wait for it. */
  public boolean hasEntry() {
    return this != OCO;
  }

  /** OCO, OTOCO and BRACKET contain an OCO pair (TAKE_PROFIT and STOP_LOSS). */
  public boolean hasOcoPair() {
    return this != OTO;
  }
}
