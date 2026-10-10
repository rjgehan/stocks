package com.stocks.contract.portfolio;

import com.stocks.common.money.Rounding;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Unit-value arithmetic (§16.2), pure. Every result is {@link Rounding#UNIT} (scale 18, HALF_EVEN).
 *
 * <p>Each account behaves like a fund that issues units at its current unit value U: an external flow F (deposit,
 * withdrawal, contribution adjustment) buys or redeems {@code F / U} units at the <b>pre-flow</b> U, so a flow never
 * changes U, and the time-weighted return {@code U(b) / U(a) − 1} is exact whatever the deposit timing. The engine
 * (WS3) calls {@link #openRun} when it opens a run (INITIAL transfer) and {@link #contribution} for every
 * contribution flow inside {@code ApplyCashTransfersCmd}; WS4 values accounts with {@link #unitValue} /
 * {@link #unitValueOrLast}.
 *
 * <pre>
 * Run 1 open (INITIAL F₀):          U = 1, units = F₀, series 1
 * Run n+1 open (reset):             U = final U of run n, units = F₀ / U, same series
 *                                   (final U = 0 → rebase: U = 1, units = F₀, series + 1)
 * Contribution F (E⁻ = equity before the flow, valued live):
 *   units⁻ &gt; 0, E⁻ &gt; 0   U = E⁻ / units⁻, Δ = F / U   (a withdrawal that empties the account: Δ = −units⁻)
 *   units⁻ = 0           U = U of the last applied transfer, Δ = F / U
 *   units⁻ &gt; 0, E⁻ = 0   rebase: U = 1, Δ = F − units⁻ (so units⁺ = F), series + 1
 * </pre>
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

  /**
   * U at any time (§16.2): {@code UNIT(equity / units)} while units are positive, else the U of the account's last
   * applied transfer ({@code lastUnitValue}; 1 when there is none).
   */
  public static BigDecimal unitValueOrLast(BigDecimal equity, BigDecimal units, BigDecimal lastUnitValue) {
    if (units != null && units.signum() > 0) {
      return unitValue(equity, units);
    }
    return lastUnitValue != null && lastUnitValue.signum() > 0 ? Rounding.UNIT.apply(lastUnitValue)
        : Rounding.UNIT.apply(BigDecimal.ONE);
  }

  /**
   * Opening a run with its INITIAL transfer (§16.2, §16.3).
   *
   * @param startingCash                 F₀ (&gt; 0)
   * @param previousFinalUnitValueOrNull {@code final_unit_value} of the player's previous run; null for run 1
   * @param previousUnitSeries           the previous run's {@code unit_series} (ignored for run 1)
   */
  public static UnitFlow openRun(BigDecimal startingCash, BigDecimal previousFinalUnitValueOrNull,
                                 int previousUnitSeries) {
    Objects.requireNonNull(startingCash, "startingCash");
    if (startingCash.signum() <= 0) {
      throw new IllegalArgumentException("startingCash must be > 0, was " + startingCash.toPlainString());
    }
    BigDecimal f = Rounding.UNIT.apply(startingCash);
    if (previousFinalUnitValueOrNull == null) {
      return new UnitFlow(Rounding.UNIT.apply(BigDecimal.ONE), f, f, 1, false);
    }
    if (previousFinalUnitValueOrNull.signum() <= 0) {
      return new UnitFlow(Rounding.UNIT.apply(BigDecimal.ONE), f, f, previousUnitSeries + 1, true);
    }
    BigDecimal u = Rounding.UNIT.apply(previousFinalUnitValueOrNull);
    BigDecimal units = unitsFor(startingCash, u);
    return new UnitFlow(u, units, units, previousUnitSeries, false);
  }

  /**
   * A contribution flow F at an instant (§16.2): DEPOSIT (+), WITHDRAWAL (−) or ADJUSTMENT treated as CONTRIBUTION
   * (±). The caller has already checked {@code |F| ≤ withdrawable} for an outflow. A {@code PNL_CORRECTION}
   * adjustment is performance, not a flow: it never calls this ({@code units_delta = 0}).
   *
   * @param flowAmount       F, signed, ≠ 0
   * @param equityBefore     E⁻, the equity at the instant before the flow ({@code ValuationService.valueNow})
   * @param unitsBefore      {@code account.units} before the flow (≥ 0)
   * @param unitSeriesBefore {@code account.unit_series} before the flow
   * @param lastUnitValue    U of the account's last applied transfer; used only when {@code unitsBefore = 0}
   * @throws IllegalArgumentException for a zero flow, or an outflow from an account that holds nothing
   */
  public static UnitFlow contribution(BigDecimal flowAmount, BigDecimal equityBefore, BigDecimal unitsBefore,
                                      int unitSeriesBefore, BigDecimal lastUnitValue) {
    Objects.requireNonNull(flowAmount, "flowAmount");
    Objects.requireNonNull(equityBefore, "equityBefore");
    Objects.requireNonNull(unitsBefore, "unitsBefore");
    if (flowAmount.signum() == 0) {
      throw new IllegalArgumentException("flowAmount must not be 0");
    }
    if (unitsBefore.signum() < 0) {
      throw new IllegalArgumentException("unitsBefore must be >= 0, was " + unitsBefore.toPlainString());
    }
    BigDecimal unitsB = Rounding.UNIT.apply(unitsBefore);
    if (unitsB.signum() == 0) {
      if (flowAmount.signum() < 0) {
        throw new IllegalArgumentException("an account without units has nothing to withdraw");
      }
      BigDecimal u = lastUnitValue != null && lastUnitValue.signum() > 0 ? Rounding.UNIT.apply(lastUnitValue)
          : Rounding.UNIT.apply(BigDecimal.ONE);
      BigDecimal delta = unitsFor(flowAmount, u);
      return new UnitFlow(u, delta, delta, unitSeriesBefore, false);
    }
    if (equityBefore.signum() <= 0) {
      if (flowAmount.signum() < 0) {
        throw new IllegalArgumentException("a wiped-out account has nothing to withdraw");
      }
      BigDecimal f = Rounding.UNIT.apply(flowAmount);
      return new UnitFlow(Rounding.UNIT.apply(BigDecimal.ONE), f.subtract(unitsB), f, unitSeriesBefore + 1, true);
    }
    BigDecimal u = unitValue(equityBefore, unitsB);
    BigDecimal delta;
    if (equityBefore.add(flowAmount).signum() <= 0) {
      delta = unitsB.negate();                     // a withdrawal that leaves E⁺ = 0.00 redeems every unit exactly
    } else {
      delta = unitsFor(flowAmount, u);
      if (unitsB.add(delta).signum() < 0) {
        delta = unitsB.negate();
      }
    }
    return new UnitFlow(u, delta, unitsB.add(delta), unitSeriesBefore, false);
  }
}
