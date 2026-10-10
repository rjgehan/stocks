package com.stocks.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Named rounding policies (§6.2, CONTRACT). <b>Every rounding site names one</b>, and every division goes through
 * {@link #divide} or names a scale and a {@link RoundingMode}: a bare {@code BigDecimal.divide(x)} throws on
 * non-terminating results (1-for-3 splits) and is a bug.
 *
 * <table>
 *   <caption>Policies</caption>
 *   <tr><th>Policy</th><th>Rule</th><th>Used for</th></tr>
 *   <tr><td>PRINCIPAL</td><td>HALF_UP to cents</td><td>execution principal ({@code qty × price})</td></tr>
 *   <tr><td>NOTIONAL_QTY</td><td>FLOOR to 5 dp</td><td>{@code floor5((notional − estFees) / price)}</td></tr>
 *   <tr><td>HOLD</td><td>CEILING to cents</td><td>buying-power holds</td></tr>
 *   <tr><td>FEE_CEIL / FEE_HALF_UP / FEE_SUBCENT_ZERO</td><td>§13</td><td>fees</td></tr>
 *   <tr><td>CASH_IN_LIEU, DIVIDEND</td><td>FLOOR to cents</td><td>corporate actions</td></tr>
 *   <tr><td>SPLIT_QTY</td><td>{@code (qty × new_rate).divide(old_rate, 5, FLOOR)}</td><td>splits, per lot</td></tr>
 *   <tr><td>SPLIT_ADJUST</td><td>{@code (price × old_rate).divide(new_rate, 4, HALF_EVEN)}</td><td>pre-ex-date prices</td></tr>
 *   <tr><td>MARKET_VALUE</td><td>HALF_EVEN to cents</td><td>per-position market value</td></tr>
 *   <tr><td>TICK_DOWN / TICK_UP</td><td>to the tick ({@link Prices#tick}), toward / away from zero</td><td>collars, walks, trailing stops</td></tr>
 *   <tr><td>UNIT</td><td>HALF_EVEN at scale 18</td><td>unit accounting (§16.2)</td></tr>
 * </table>
 */
public enum Rounding {
  PRINCIPAL(2, RoundingMode.HALF_UP),
  NOTIONAL_QTY(5, RoundingMode.FLOOR),
  HOLD(2, RoundingMode.CEILING),
  /** SEC Section 31: any positive amount is at least $0.01. */
  FEE_CEIL(2, RoundingMode.CEILING),
  /** FINRA TAF. */
  FEE_HALF_UP(2, RoundingMode.HALF_UP),
  /** CAT: below $0.01 becomes $0.00, otherwise HALF_UP to cents. */
  FEE_SUBCENT_ZERO(2, RoundingMode.HALF_UP) {
    @Override
    public BigDecimal apply(BigDecimal exact) {
      Objects.requireNonNull(exact, "exact");
      if (exact.abs().compareTo(Money.ONE_CENT) < 0) {
        return Money.ZERO;
      }
      return exact.setScale(2, RoundingMode.HALF_UP);
    }
  },
  CASH_IN_LIEU(2, RoundingMode.FLOOR),
  DIVIDEND(2, RoundingMode.FLOOR),
  SPLIT_QTY(5, RoundingMode.FLOOR),
  SPLIT_ADJUST(4, RoundingMode.HALF_EVEN),
  MARKET_VALUE(2, RoundingMode.HALF_EVEN),
  /** Round to the price tick toward zero. */
  TICK_DOWN(-1, RoundingMode.DOWN) {
    @Override
    public BigDecimal apply(BigDecimal price) {
      return toTick(price, RoundingMode.DOWN);
    }
  },
  /** Round to the price tick away from zero. */
  TICK_UP(-1, RoundingMode.UP) {
    @Override
    public BigDecimal apply(BigDecimal price) {
      return toTick(price, RoundingMode.UP);
    }
  },
  UNIT(18, RoundingMode.HALF_EVEN);

  private final int scale;
  private final RoundingMode mode;

  Rounding(int scale, RoundingMode mode) {
    this.scale = scale;
    this.mode = mode;
  }

  /** Result scale; -1 for the tick policies, whose scale depends on the price (2 or 4). */
  public int scale() {
    return scale;
  }

  public RoundingMode mode() {
    return mode;
  }

  /** Rounds an exact value under this policy. */
  public BigDecimal apply(BigDecimal exact) {
    return Objects.requireNonNull(exact, "exact").setScale(scale, mode);
  }

  /** {@code dividend / divisor} at this policy's scale and mode. Not defined for the tick policies. */
  public BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
    if (scale < 0) {
      throw new UnsupportedOperationException(name() + " has no fixed scale; divide first, then apply()");
    }
    return dividend.divide(divisor, scale, mode);
  }

  /** {@code a × b}, rounded under this policy (e.g. {@code PRINCIPAL.multiply(qty, price)}). */
  public BigDecimal multiply(BigDecimal a, BigDecimal b) {
    return apply(a.multiply(b));
  }

  /** Split quantity per lot: {@code (qty × newRate).divide(oldRate, 5, FLOOR)}; never {@code newRate / oldRate}. */
  public static BigDecimal splitQty(BigDecimal qty, BigDecimal oldRate, BigDecimal newRate) {
    return SPLIT_QTY.divide(qty.multiply(newRate), oldRate);
  }

  /** Split-adjusted reference price: {@code (price × oldRate).divide(newRate, 4, HALF_EVEN)}. */
  public static BigDecimal splitAdjust(BigDecimal price, BigDecimal oldRate, BigDecimal newRate) {
    return SPLIT_ADJUST.divide(price.multiply(oldRate), newRate);
  }

  private static BigDecimal toTick(BigDecimal price, RoundingMode mode) {
    Objects.requireNonNull(price, "price");
    BigDecimal tick = Prices.tick(price);
    BigDecimal rounded = price.divide(tick, 0, mode).multiply(tick);
    // Rounding up across $1.00 (0.99995 -> 1.0000) lands on a whole-cent price, which is on both ticks.
    return rounded.setScale(Prices.SCALE, RoundingMode.UNNECESSARY);
  }
}
