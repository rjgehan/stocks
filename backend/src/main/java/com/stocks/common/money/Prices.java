package com.stocks.common.money;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Prices: scale ≤ 4, {@code NUMERIC(19,4)}, rendered at scale 4 ({@code "227.4000"}) (§6.2). Tick (Reg NMS 612):
 * $0.01 when the price is ≥ $1.00, $0.0001 below. Limit, stop and trail amounts off tick → {@code INVALID_TICK}.
 */
public final class Prices {

  public static final int SCALE = 4;
  public static final BigDecimal ONE_DOLLAR = new BigDecimal("1.00");
  public static final BigDecimal CENT_TICK = new BigDecimal("0.01");
  public static final BigDecimal SUBDOLLAR_TICK = new BigDecimal("0.0001");

  private Prices() {}

  /** The tick for a price: $0.01 at or above $1.00, $0.0001 below (by absolute value). */
  public static BigDecimal tick(BigDecimal price) {
    return price.abs().compareTo(ONE_DOLLAR) >= 0 ? CENT_TICK : SUBDOLLAR_TICK;
  }

  public static boolean isOnTick(BigDecimal price) {
    return price.remainder(tick(price)).signum() == 0;
  }

  /** Validates that a price is on tick and normalizes it to scale 4. */
  public static BigDecimal requireOnTick(String field, BigDecimal price) {
    if (price == null) {
      throw ApiException.validation(field, field + " is required");
    }
    if (!Money.hasAtMostDecimals(price, SCALE) || !isOnTick(price)) {
      throw ApiException.of(ErrorCode.INVALID_TICK,
              field + " " + price.toPlainString() + " is not on the " + tick(price).toPlainString() + " tick")
          .withField(field, ErrorCode.INVALID_TICK, "Tick is $0.01 at or above $1.00, $0.0001 below");
    }
    return normalize(price);
  }

  /** Exact scale-4 view of a price that has at most 4 decimals; throws if it has more. */
  public static BigDecimal normalize(BigDecimal price) {
    return price.setScale(SCALE, RoundingMode.UNNECESSARY);
  }
}
