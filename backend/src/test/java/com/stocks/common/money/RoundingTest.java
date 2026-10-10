package com.stocks.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoundingTest {

  private static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  @ParameterizedTest(name = "{0}({1}) = {2}")
  @CsvSource({
      // policy,          exact,          expected
      "PRINCIPAL_BUY,     227.415,        227.42",
      "PRINCIPAL_BUY,     227.410001,     227.42",
      "PRINCIPAL_BUY,     0.00499,        0.01",
      "PRINCIPAL_BUY,     227.41,         227.41",
      "PRINCIPAL_SELL,    227.419999,     227.41",
      "PRINCIPAL_SELL,    0.00499,        0.00",
      "PRINCIPAL_SELL,    227.41,         227.41",
      "NOTIONAL_QTY,      2.198789999,    2.19878",
      "NOTIONAL_QTY,      0.000009,       0.00000",
      "HOLD,              231.951,        231.96",
      "HOLD,              231.95,         231.95",
      "FEE_CEIL,          1.0300,         1.03",
      "FEE_CEIL,          0.0001,         0.01",
      "FEE_CEIL,          1.030000001,    1.04",
      "FEE_HALF_UP,       0.0195,         0.02",
      "FEE_HALF_UP,       0.0149,         0.01",
      "FEE_SUBCENT_ZERO,  0.003,          0.00",
      "FEE_SUBCENT_ZERO,  0.0099999,      0.00",
      "FEE_SUBCENT_ZERO,  0.012,          0.01",
      "FEE_SUBCENT_ZERO,  0.015,          0.02",
      "FEE_SUBCENT_ZERO,  0.30,           0.30",
      "CASH_IN_LIEU,      12.349,         12.34",
      "DIVIDEND,          0.999,          0.99",
      "MARKET_VALUE,      100.125,        100.12",
      "MARKET_VALUE,      100.135,        100.14",
      "UNIT,              1.0000000000000000005,  1.000000000000000000",
      "UNIT,              1.0000000000000000015,  1.000000000000000002",
      "SPLIT_QTY,         1.234569,       1.23456",
      "SPLIT_ADJUST,      227.41255,      227.4126",
  })
  void namedPolicies(Rounding policy, String exact, String expected) {
    BigDecimal r = policy.apply(d(exact));
    assertThat(r).isEqualByComparingTo(d(expected));
    assertThat(r.scale()).isEqualTo(policy.scale());
  }

  @ParameterizedTest(name = "{0}({1}) = {2}")
  @CsvSource({
      "TICK_DOWN, 227.4199, 227.4100",
      "TICK_UP,   227.4101, 227.4200",
      "TICK_DOWN, 227.41,   227.4100",
      "TICK_UP,   227.41,   227.4100",
      "TICK_DOWN, 1.00999,  1.0000",
      "TICK_DOWN, 0.99999,  0.9999",
      "TICK_UP,   0.99981,  0.9999",
      "TICK_UP,   0.99991,  1.0000",
      "TICK_UP,   0.99995,  1.0000",
      "TICK_DOWN, 0.123456, 0.1234",
      "TICK_UP,   0.123401, 0.1235",
      "TICK_DOWN, -1.019,   -1.0100",
      "TICK_UP,   -1.011,   -1.0200",
  })
  void tickPolicies(Rounding policy, String price, String expected) {
    BigDecimal r = policy.apply(d(price));
    assertThat(r).isEqualTo(d(expected)); // scale 4 exactly
    assertThat(Prices.isOnTick(r)).isTrue();
  }

  @Test
  void splitQtyNeverComputesTheRatioAlone() {
    // 1-for-3 reverse split: 10 shares → 3.33333 (FLOOR); new_rate/old_rate = 1/3 would not terminate
    assertThat(Rounding.splitQty(d("10"), d("3"), d("1"))).isEqualTo(d("3.33333"));
    // 3-for-2 on a fractional lot
    assertThat(Rounding.splitQty(d("1.00001"), d("2"), d("3"))).isEqualTo(d("1.50001"));
    // 1-for-8
    assertThat(Rounding.splitQty(d("7.5"), d("8"), d("1"))).isEqualTo(d("0.93750"));
  }

  @Test
  void splitAdjustUsesHalfEvenAtScale4() {
    // 2:1 forward split: a pre-split price of 100.0001 becomes 50.0000 (50.00005 → HALF_EVEN)
    assertThat(Rounding.splitAdjust(d("100.0001"), d("1"), d("2"))).isEqualTo(d("50.0000"));
    assertThat(Rounding.splitAdjust(d("100.0003"), d("1"), d("2"))).isEqualTo(d("50.0002"));
    // 1:3 reverse: price triples
    assertThat(Rounding.splitAdjust(d("1.2345"), d("3"), d("1"))).isEqualTo(d("3.7035"));
    // 3:2 forward: non-terminating division is fine with a named policy
    assertThat(Rounding.splitAdjust(d("10.00"), d("2"), d("3"))).isEqualTo(d("6.6667"));
  }

  @Test
  void divideAndMultiplyNameTheirPolicy() {
    assertThat(Rounding.NOTIONAL_QTY.divide(d("498.97"), d("227.41"))).isEqualTo(d("2.19414"));
    assertThat(Rounding.PRINCIPAL_BUY.multiply(d("2.19414"), d("227.41"))).isEqualTo(d("498.97"));
    // §6.2: principal rounds against the player, so a 0.00001 x 499.00 buy costs a cent, never nothing
    assertThat(Rounding.principal(com.stocks.common.domain.Side.BUY, d("0.00001"), d("499.00"))).isEqualTo(d("0.01"));
    assertThat(Rounding.principal(com.stocks.common.domain.Side.SELL, d("0.00001"), d("499.00")))
        .isEqualTo(d("0.00"));
    assertThat(Rounding.principal(com.stocks.common.domain.Side.BUY, d("1"), d("0.5049"))).isEqualTo(d("0.51"));
    assertThat(Rounding.UNIT.divide(d("1"), d("3"))).isEqualTo(d("0.333333333333333333"));
    assertThatThrownBy(() -> Rounding.TICK_DOWN.divide(d("1"), d("3")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void feeGoldenCasesFromSection13() {
    // Sell 1,000 shares at $50.00 on 2026-10-09: SEC 0.0000206 × 50,000 = 1.03; CAT 0.000003 × 1,000 = 0.003 → 0.00
    assertThat(Rounding.FEE_CEIL.apply(d("0.000020600000").multiply(d("50000.00")))).isEqualTo(d("1.03"));
    assertThat(Rounding.FEE_SUBCENT_ZERO.apply(d("0.000003000000").multiply(d("1000")))).isEqualTo(d("0.00"));
    // Buy 4,000 shares: CAT 0.012 → 0.01
    assertThat(Rounding.FEE_SUBCENT_ZERO.apply(d("0.000003000000").multiply(d("4000")))).isEqualTo(d("0.01"));
    // Sell $5.00 notional: SEC 0.000103 → 0.01
    assertThat(Rounding.FEE_CEIL.apply(d("0.000020600000").multiply(d("5.00")))).isEqualTo(d("0.01"));
    // 2027 TAF on 100,000 shares: 19.50 (the 9.79 cap is applied by FeeCalculator, not by rounding)
    assertThat(Rounding.FEE_HALF_UP.apply(d("0.000195000000").multiply(d("100000")))).isEqualTo(d("19.50"));
  }
}
