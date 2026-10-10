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

  @Test
  void runOneOpensAtUnitValueOne() {
    UnitFlow f = UnitMath.openRun(d("500.00"), null, 7);
    assertThat(f.unitValue()).isEqualByComparingTo("1").satisfies(v -> assertThat(v.scale()).isEqualTo(18));
    assertThat(f.unitsDelta()).isEqualByComparingTo("500");
    assertThat(f.unitsAfter()).isEqualByComparingTo("500");
    assertThat(f.unitSeriesAfter()).isEqualTo(1);
    assertThat(f.rebased()).isFalse();
  }

  @Test
  void aResetContinuesTheSeriesAtTheFinalUnitValue() {
    UnitFlow f = UnitMath.openRun(d("500.00"), d("0.6"), 1);           // twr_reset: run 1 closed at U = 0.60
    assertThat(f.unitValue()).isEqualByComparingTo("0.6");
    assertThat(f.unitsAfter().setScale(10, RoundingMode.HALF_EVEN)).isEqualByComparingTo("833.3333333333");
    assertThat(f.unitSeriesAfter()).isEqualTo(1);
    assertThat(f.rebased()).isFalse();
  }

  @Test
  void aResetAfterAWipeOutRebases() {
    UnitFlow f = UnitMath.openRun(d("500.00"), BigDecimal.ZERO, 1);
    assertThat(f.unitValue()).isEqualByComparingTo("1");
    assertThat(f.unitsAfter()).isEqualByComparingTo("500");
    assertThat(f.unitSeriesAfter()).isEqualTo(2);
    assertThat(f.rebased()).isTrue();
  }

  @Test
  void aDepositIsIssuedAtThePreFlowUnitValue() {
    UnitFlow f = UnitMath.contribution(d("500.00"), d("560.00"), d("500"), 1, d("1"));
    assertThat(f.unitValue()).isEqualByComparingTo("1.12");
    assertThat(f.unitsAfter().setScale(10, RoundingMode.HALF_EVEN)).isEqualByComparingTo("946.4285714286");
    assertThat(f.unitSeriesAfter()).isEqualTo(1);
    assertThat(f.rebased()).isFalse();
    // U after the flow is unchanged
    assertThat(UnitMath.unitValue(d("1060.00"), f.unitsAfter()).setScale(10, RoundingMode.HALF_EVEN))
        .isEqualByComparingTo("1.12");
  }

  @Test
  void aWithdrawalRedeemsUnitsAndAFullWithdrawalRedeemsThemAll() {
    UnitFlow partial = UnitMath.contribution(d("-100.00"), d("600.00"), d("500"), 1, d("1"));
    assertThat(partial.unitValue()).isEqualByComparingTo("1.2");
    assertThat(partial.unitsDelta().setScale(10, RoundingMode.HALF_EVEN)).isEqualByComparingTo("-83.3333333333");
    UnitFlow full = UnitMath.contribution(d("-600.00"), d("600.00"), d("500"), 1, d("1"));
    assertThat(full.unitsDelta()).isEqualByComparingTo("-500");
    assertThat(full.unitsAfter()).isEqualByComparingTo("0");
  }

  @Test
  void anEmptiedAccountIssuesAtItsLastUnitValue() {
    UnitFlow f = UnitMath.contribution(d("240.00"), d("0.00"), BigDecimal.ZERO, 1, d("1.2"));
    assertThat(f.unitValue()).isEqualByComparingTo("1.2");
    assertThat(f.unitsAfter()).isEqualByComparingTo("200");
    assertThat(f.rebased()).isFalse();
    assertThat(UnitMath.contribution(d("10.00"), d("0.00"), BigDecimal.ZERO, 1, null).unitValue())
        .isEqualByComparingTo("1");
  }

  @Test
  void aContributionIntoAWipedOutAccountRebases() {
    UnitFlow f = UnitMath.contribution(d("500.00"), d("0.00"), d("500"), 1, d("1"));
    assertThat(f.unitValue()).isEqualByComparingTo("1");
    assertThat(f.unitsDelta()).isEqualByComparingTo("0");                   // F − units⁻, so units⁺ = F (I4 holds)
    assertThat(f.unitsAfter()).isEqualByComparingTo("500");
    assertThat(f.unitSeriesAfter()).isEqualTo(2);
    assertThat(f.rebased()).isTrue();
    UnitFlow g = UnitMath.contribution(d("100.00"), d("0.00"), d("37.5"), 3, d("0.01"));
    assertThat(g.unitsDelta()).isEqualByComparingTo("62.5");
    assertThat(g.unitSeriesAfter()).isEqualTo(4);
  }

  @Test
  void invalidFlowsAreRejected() {
    assertThatThrownBy(() -> UnitMath.contribution(BigDecimal.ZERO, d("1.00"), d("1"), 1, d("1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> UnitMath.contribution(d("-1.00"), d("0.00"), BigDecimal.ZERO, 1, d("1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> UnitMath.contribution(d("-1.00"), d("0.00"), d("5"), 1, d("1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> UnitMath.contribution(d("1.00"), d("1.00"), d("-1"), 1, d("1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> UnitMath.openRun(BigDecimal.ZERO, null, 1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unitValueOrLastFallsBackWithoutUnits() {
    assertThat(UnitMath.unitValueOrLast(d("10.00"), BigDecimal.ZERO, d("1.5"))).isEqualByComparingTo("1.5");
    assertThat(UnitMath.unitValueOrLast(d("10.00"), BigDecimal.ZERO, null)).isEqualByComparingTo("1");
    assertThat(UnitMath.unitValueOrLast(d("10.00"), d("4"), null)).isEqualByComparingTo("2.5");
  }
}
