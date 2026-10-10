package com.stocks.contract.portfolio;

import com.stocks.common.money.Rounding;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Unit-value arithmetic (§16.2), pure. Both results are {@link Rounding#UNIT} (scale 18, HALF_EVEN). The flow cases
 * (empty account, rebase) are the caller's: see §16.2.
 */
public final class UnitMath {

  private UnitMath() {}

  /**
   * {@code U = UNIT(equity / units)}.
   *
   * @throws IllegalArgumentException if {@code units} is not positive (use the last transfer's U, §16.2)
   */
  public static BigDecimal unitValue(BigDecimal equity, BigDecimal units) {
    Objects.requireNonNull(equity, "equity");
    if (Objects.requireNonNull(units, "units").signum() <= 0) {
      throw new IllegalArgumentException("units must be > 0, was " + units.toPlainString());
    }
    return Rounding.UNIT.divide(equity, units);
  }

  /**
   * {@code units_delta = UNIT(flow / U)}; negative for a withdrawal.
   *
   * @throws IllegalArgumentException if {@code unitValue} is not positive (a wiped-out account rebases, §16.2)
   */
  public static BigDecimal unitsFor(BigDecimal flowAmount, BigDecimal unitValue) {
    Objects.requireNonNull(flowAmount, "flowAmount");
    if (Objects.requireNonNull(unitValue, "unitValue").signum() <= 0) {
      throw new IllegalArgumentException("unitValue must be > 0, was " + unitValue.toPlainString());
    }
    return Rounding.UNIT.divide(flowAmount, unitValue);
  }
}
