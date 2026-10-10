package com.stocks.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Scale;

/** Property-based checks (jqwik) of the rounding guarantees the engine relies on (§6.2, §26). */
class RoundingPropertiesTest {

  @Property(tries = 500)
  void holdNeverUnderReservesAndPrincipalIsWithinHalfACent(
      @ForAll @BigRange(min = "0.00001", max = "200000") @Scale(5) BigDecimal qty,
      @ForAll @BigRange(min = "0.0001", max = "100000") @Scale(4) BigDecimal price) {
    BigDecimal exact = qty.multiply(price);
    assertThat(Rounding.HOLD.apply(exact)).isGreaterThanOrEqualTo(exact);
    assertThat(Rounding.PRINCIPAL.apply(exact).subtract(exact).abs()).isLessThanOrEqualTo(new BigDecimal("0.005"));
  }

  @Property(tries = 500)
  void notionalQtyNeverSpendsMoreThanTheNotional(
      @ForAll @BigRange(min = "1.00", max = "100000") @Scale(2) BigDecimal notional,
      @ForAll @BigRange(min = "0.0001", max = "5000") @Scale(4) BigDecimal price) {
    BigDecimal qty = Rounding.NOTIONAL_QTY.divide(notional, price);
    assertThat(qty.multiply(price)).isLessThanOrEqualTo(notional);
    assertThat(qty.scale()).isEqualTo(5);
  }

  @Property(tries = 500)
  void splitQtyNeverCreatesShares(
      @ForAll @BigRange(min = "0.00001", max = "200000") @Scale(5) BigDecimal qty,
      @ForAll @BigRange(min = "1", max = "50") @Scale(0) BigDecimal oldRate,
      @ForAll @BigRange(min = "1", max = "50") @Scale(0) BigDecimal newRate) {
    BigDecimal after = Rounding.splitQty(qty, oldRate, newRate);
    assertThat(after.multiply(oldRate)).isLessThanOrEqualTo(qty.multiply(newRate));
    assertThat(after.scale()).isEqualTo(5);
  }

  @Property(tries = 500)
  void tickRoundingBracketsThePrice(@ForAll @BigRange(min = "0.0001", max = "100000") @Scale(6) BigDecimal price) {
    BigDecimal down = Rounding.TICK_DOWN.apply(price);
    BigDecimal up = Rounding.TICK_UP.apply(price);
    assertThat(down).isLessThanOrEqualTo(price);
    assertThat(up).isGreaterThanOrEqualTo(price);
    assertThat(Prices.isOnTick(down)).isTrue();
    assertThat(Prices.isOnTick(up)).isTrue();
  }
}
