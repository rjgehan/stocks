package com.stocks.marketdata.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.trading.testkit.TestCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §30 SIM options: Black-Scholes values, quotes on the cent around them, the expiration and strike grids. */
class SimOptionsTest {

  static final Instant T = Instant.parse("2026-10-09T14:00:00Z");   // Friday 10:00 ET

  static OptionSymbol.Contract c(String occ) {
    return OptionSymbol.parse(occ).orElseThrow();
  }

  @Test
  void theNormalCdfIsAccurate() {
    assertThat(SimOptions.cdf(0)).isCloseTo(0.5, within(1e-7));
    assertThat(SimOptions.cdf(1.96)).isCloseTo(0.9750021, within(1e-6));
    assertThat(SimOptions.cdf(-1)).isCloseTo(0.1586553, within(1e-6));
  }

  @Test
  void valuesRespectIntrinsicValueAndPutCallParity() {
    OptionSymbol.Contract call = c("SIMA261120C00100000");
    OptionSymbol.Contract put = c("SIMA261120P00100000");
    double callV = SimOptions.value(call, 104, T, 0.25);
    double putV = SimOptions.value(put, 104, T, 0.25);
    assertThat(callV).isGreaterThan(4);                               // above intrinsic before expiration
    double years = java.time.Duration.between(T, com.stocks.common.time.Et.at(call.expiration(),
        java.time.LocalTime.of(16, 0))).toSeconds() / (365.0 * 24 * 3600);
    assertThat(callV - putV).isCloseTo(104 - 100 * Math.exp(-SimOptions.RATE * years), within(1e-6));
    // at the close of the expiration day the value is intrinsic
    Instant close = com.stocks.common.time.Et.at(call.expiration(), java.time.LocalTime.of(16, 0));
    assertThat(SimOptions.value(call, 104, close, 0.25)).isEqualTo(4.0);
    assertThat(SimOptions.value(put, 104, close, 0.25)).isEqualTo(0.0);
  }

  @Test
  void quotesAreTwoSidedOnTheCentAroundTheValue() {
    OptionSymbol.Contract call = c("SIMA261120C00100000");
    Quote u = new Quote("SIMA", new BigDecimal("103.99"), 100, new BigDecimal("104.01"), 100, "SIM", "SIM", T, T,
        DataQuality.SIMULATED, "simulated");
    Quote q = SimOptions.quote(call, u, LiquidityTier.MEGA).orElseThrow();
    assertThat(q.twoSided()).isTrue();
    assertThat(q.bid().stripTrailingZeros().scale()).isLessThanOrEqualTo(2);
    assertThat(q.ask().stripTrailingZeros().scale()).isLessThanOrEqualTo(2);
    double v = SimOptions.value(call, 104, T, 0.25);
    assertThat(q.bid().doubleValue()).isLessThan(v);
    assertThat(q.ask().doubleValue()).isGreaterThan(v);
    assertThat(q.quality()).isEqualTo(DataQuality.SIMULATED);
    assertThat(q.ts()).isEqualTo(T);
  }

  @Test
  void expirationsAreWeeklyFridaysAndMonthlyThirdFridays() {
    List<LocalDate> e = SimOptions.expirations(new TestCalendar(), LocalDate.of(2026, 10, 9));
    assertThat(e).first().isEqualTo(LocalDate.of(2026, 10, 9));       // a Friday lists its own 0DTE expiration
    assertThat(e).contains(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 11, 20), LocalDate.of(2027, 4, 16));
    assertThat(e).isSorted().doesNotHaveDuplicates();
    // Good Friday 2027 (26 March) is a holiday: the weekly expiration moves to Thursday
    assertThat(SimOptions.expirations(new TestCalendar(), LocalDate.of(2027, 3, 22)))
        .contains(LocalDate.of(2027, 3, 25)).doesNotContain(LocalDate.of(2027, 3, 26));
  }

  @Test
  void strikesStepAroundTheMoney() {
    List<BigDecimal> s = SimOptions.strikes(new BigDecimal("187.40"));
    assertThat(s).hasSize(2 * SimOptions.STRIKES_EACH_SIDE + 1);
    assertThat(s.get(SimOptions.STRIKES_EACH_SIDE)).isEqualByComparingTo("185");
    assertThat(s.get(1).subtract(s.get(0))).isEqualByComparingTo("5");
    assertThat(SimOptions.strikes(new BigDecimal("3.10"))).allSatisfy(k -> assertThat(k.signum()).isPositive());
  }
}
