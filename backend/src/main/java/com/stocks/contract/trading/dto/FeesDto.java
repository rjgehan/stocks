package com.stocks.contract.trading.dto;

import java.math.BigDecimal;

/** §18.3 {@code Fees} (§13): regulatory fees on an execution or estimate, scale 2. */
public record FeesDto(BigDecimal sec, BigDecimal taf, BigDecimal cat, BigDecimal total) {

  /** All four amounts {@code 0.00}. */
  public static FeesDto zero() {
    BigDecimal z = new BigDecimal("0.00");
    return new FeesDto(z, z, z, z);
  }
}
