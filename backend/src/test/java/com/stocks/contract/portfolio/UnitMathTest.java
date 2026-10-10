package com.stocks.contract.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/** §16.2 unit arithmetic, checked against the {@code twr_deposit} and {@code twr_tiny_equity} vectors. */
class UnitMathTest {

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  @Test
  void twrDepositVector() {
    BigDecimal units = d("500");
    BigDecimal u = UnitMath.unitValue(d("560.00"), units);                 // Tue 11:00, E⁻ = 560
    assertThat(u).isEqualByComparingTo("1.12").satisfies(v -> assertThat(v.scale()).isEqualTo(18));
    BigDecimal delta = UnitMath.unitsFor(d("500.00"), u);
    BigDecimal after = units.add(delta);
    assertThat(after.setScale(10, RoundingMode.HALF_EVEN)).isEqualByComparingTo("946.4285714286");
    BigDecimal eod = UnitMath.unitValue(d("1100.00"), after);                // Tue 20:00
    assertThat(eod.setScale(10, RoundingMode.HALF_EVEN)).isEqualByComparingTo("1.1622641509");
    // a flow never changes U
    assertThat(UnitMath.unitValue(d("1060.00"), after).setScale(10, RoundingMode.HALF_EVEN))
        .isEqualByComparingTo("1.1200000000");
  }

  @Test
  void tinyEquityStillMintsUnits() {
    BigDecimal u = UnitMath.unitValue(d("0.40"), d("500"));
    assertThat(u).isEqualByComparingTo("0.0008");
    assertThat(UnitMath.unitsFor(d("500.00"), u)).isEqualByComparingTo("625000");
  }

  @Test
  void nonPositiveDivisorsAreTheCallersCase() {
    assertThatThrownBy(() -> UnitMath.unitValue(d("1.00"), BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> UnitMath.unitsFor(d("1.00"), BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
  }
}
