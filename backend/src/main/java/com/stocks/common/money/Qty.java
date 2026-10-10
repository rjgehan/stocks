package com.stocks.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Share quantities: scale ≤ 5, {@code NUMERIC(18,5)}, &gt; 0, at most 200,000 (§6.2). Whole shares unless fractional
 * (§11.1). Order-level validation (which error code applies) lives in the trading validators.
 */
public final class Qty {

  public static final int SCALE = 5;
  public static final BigDecimal ZERO = new BigDecimal("0.00000");
  public static final BigDecimal MIN_FRACTION = new BigDecimal("0.00001");
  public static final BigDecimal MAX = new BigDecimal("200000");

  private Qty() {}

  /** True if 0 &lt; qty ≤ 200,000 with at most 5 decimals. */
  public static boolean isValid(BigDecimal qty) {
    return qty != null
        && qty.signum() > 0
        && qty.compareTo(MAX) <= 0
        && Money.hasAtMostDecimals(qty, SCALE);
  }

  public static boolean isWhole(BigDecimal qty) {
    return qty.stripTrailingZeros().scale() <= 0;
  }

  /** Exact scale-5 view of a quantity with at most 5 decimals; throws if it has more. */
  public static BigDecimal normalize(BigDecimal qty) {
    return qty.setScale(SCALE, RoundingMode.UNNECESSARY);
  }
}
