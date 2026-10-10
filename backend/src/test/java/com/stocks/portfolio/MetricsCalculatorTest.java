package com.stocks.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.DataQuality;
import com.stocks.portfolio.MetricsCalculator.Flow;
import com.stocks.portfolio.MetricsCalculator.UnitPoint;
import com.stocks.portfolio.config.PerformanceProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** §16.2 / §16.5 / §16.6 metrics. */
class MetricsCalculatorTest {

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  static UnitPoint u(String v, int series) {
    return new UnitPoint(d(v), series);
  }

  @Test
  void twrIsTheUnitValueRatioOnOneSeriesAndMinus100AcrossARebase() {
    assertThat(MetricsCalculator.twr(u("1", 1), u("1.1622641509", 1))).isEqualByComparingTo("0.1622641509");
    assertThat(MetricsCalculator.twr(u("1", 1), u("0.612", 1))).isEqualByComparingTo("-0.388");
    assertThat(MetricsCalculator.twr(u("0.0008", 1), u("5", 2))).isEqualByComparingTo("-1");
    assertThat(MetricsCalculator.twr(u("1", 1), u("1", 1)).scale()).isEqualTo(10);
  }

  @Test
  void ratioIsNullWithoutABase() {
    assertThat(MetricsCalculator.ratioMinusOne(d("1"), null)).isNull();
    assertThat(MetricsCalculator.ratioMinusOne(null, d("1"))).isNull();
    assertThat(MetricsCalculator.ratioMinusOne(d("1"), BigDecimal.ZERO)).isNull();
  }

  @Test
  void excessSubtractsTheBenchmarkReturn() {
    assertThat(MetricsCalculator.excess(d("0.10"), d("1.000000000000"), d("1.050000000000")))
        .isEqualByComparingTo("0.05");
    assertThat(MetricsCalculator.excess(d("0.10"), null, d("1.05"))).isNull();
    assertThat(MetricsCalculator.excess(null, d("1"), d("1.05"))).isNull();
  }

  @Test
  void maxDrawdownIsTheWorstFallFromAPeak() {
    assertThat(MetricsCalculator.maxDrawdown(List.of())).isEqualByComparingTo("0");
    assertThat(MetricsCalculator.maxDrawdown(List.of(u("1", 1)))).isEqualByComparingTo("0");
    assertThat(MetricsCalculator.maxDrawdown(List.of(u("1", 1), u("1.2", 1), u("0.9", 1), u("1.3", 1), u("1.17", 1))))
        .isEqualByComparingTo("0.25");
    assertThat(MetricsCalculator.maxDrawdown(List.of(u("1", 1), u("2", 2)))).isEqualByComparingTo("1");
    assertThat(MetricsCalculator.maxDrawdown(List.of(u("0", 1), u("0", 1)))).isEqualByComparingTo("0");
  }

  @Test
  void winRateAndMedian() {
    assertThat(MetricsCalculator.winRate(1, 3)).isEqualByComparingTo("0.3333333333");
    assertThat(MetricsCalculator.winRate(0, 0)).isNull();
    assertThat(MetricsCalculator.median(List.of())).isNull();
    assertThat(MetricsCalculator.median(Arrays.asList(d("0.3"), null, d("0.1"), d("0.2")))).isEqualByComparingTo("0.2");
    assertThat(MetricsCalculator.median(List.of(d("0.1"), d("0.4")))).isEqualByComparingTo("0.25");
  }

  @Test
  void moneyWeightedReturnWeighsFlowsByTimeInvested() {
    Instant a = Instant.parse("2026-10-05T00:00:00Z");
    Instant b = Instant.parse("2026-10-15T00:00:00Z");
    // $500 at the start, $500 halfway: denominator 500 + 250 = 750; P&L 75 → 10%
    List<Flow> flows = List.of(new Flow(a, d("500")), new Flow(Instant.parse("2026-10-10T00:00:00Z"), d("500")));
    assertThat(MetricsCalculator.moneyWeightedReturn(d("75"), BigDecimal.ZERO, flows, a, b))
        .isEqualByComparingTo("0.1");
    assertThat(MetricsCalculator.moneyWeightedReturn(d("1"), BigDecimal.ZERO, List.of(), a, b)).isNull();
    assertThat(MetricsCalculator.moneyWeightedReturn(d("1"), BigDecimal.ONE, List.of(), b, a)).isNull();
    // flows before the window are already in E(a)
    assertThat(MetricsCalculator.moneyWeightedReturn(d("10"), d("100"),
        List.of(new Flow(a.minusSeconds(60), d("100"))), a, b)).isEqualByComparingTo("0.1");
  }

  @Test
  void qualitiesOrderFromBestToWorst() {
    assertThat(Qualities.worst(List.of())).isEqualTo(DataQuality.REALTIME_CONSOLIDATED);
    assertThat(Qualities.worst(List.of(DataQuality.SIMULATED, DataQuality.STALE, DataQuality.SYNTHETIC)))
        .isEqualTo(DataQuality.STALE);
    assertThat(Qualities.worst(Arrays.asList(DataQuality.REALTIME_SINGLE_VENUE, null))).isEqualTo(DataQuality.NONE);
    assertThat(Qualities.worse(DataQuality.SIMULATED, DataQuality.REALTIME_SINGLE_VENUE))
        .isEqualTo(DataQuality.SIMULATED);
    assertThat(Qualities.degraded(DataQuality.STALE)).isTrue();
    assertThat(Qualities.degraded(DataQuality.DELAYED_15M)).isFalse();
    assertThat(Qualities.severity(null)).isEqualTo(Qualities.severity(DataQuality.NONE));
  }

  @Test
  void performanceDefaultsMirrorApplicationYml() {
    PerformanceProperties p = PerformanceProperties.defaults();
    assertThat(p.lowEquityWarning()).isEqualByComparingTo("1.00");
    assertThat(p.eodDelay()).hasMinutes(20);
    assertThat(p.benchmarkSymbol()).isEqualTo("SPY");
    assertThat(p.leaderboardRefresh()).hasSeconds(5);
    assertThat(p.intradayRetentionDays()).isEqualTo(14);
  }

  /**
   * §26 property: TWR is independent of deposit timing when per-period returns are fixed. Two sub-periods with
   * returns r1 and r2; a deposit of any size at any boundary leaves the chained TWR at (1 + r1)(1 + r2) − 1.
   */
  @Property(tries = 300)
  void aDepositAtAnyInstantLeavesTheTwrUnchanged(
      @ForAll @BigRange(min = "-0.9", max = "2") BigDecimal r1,
      @ForAll @BigRange(min = "-0.9", max = "2") BigDecimal r2,
      @ForAll @IntRange(min = 0, max = 100_000) int depositDollars,
      @ForAll @IntRange(min = 1, max = 5_000) int startDollars) {
    BigDecimal f0 = BigDecimal.valueOf(startDollars).setScale(2);
    var open = com.stocks.contract.portfolio.UnitMath.openRun(f0, null, 1);
    BigDecimal units = open.unitsAfter();
    BigDecimal e1 = com.stocks.common.money.Money.cents(
        f0.multiply(BigDecimal.ONE.add(r1)).setScale(2, java.math.RoundingMode.HALF_EVEN));
    if (e1.signum() <= 0 || depositDollars == 0) {
      return;
    }
    BigDecimal uBefore = com.stocks.contract.portfolio.UnitMath.unitValue(e1, units);
    var flow = com.stocks.contract.portfolio.UnitMath.contribution(BigDecimal.valueOf(depositDollars).setScale(2), e1,
        units, 1, BigDecimal.ONE);
    BigDecimal e1After = e1.add(BigDecimal.valueOf(depositDollars));
    BigDecimal uAfter = com.stocks.contract.portfolio.UnitMath.unitValue(e1After, flow.unitsAfter());
    // the flow does not move U (to well below a cent of equity)
    assertThat(uAfter.subtract(uBefore).abs()).isLessThan(new BigDecimal("0.000000001"));
    BigDecimal e2 = e1After.multiply(BigDecimal.ONE.add(r2));
    BigDecimal u2 = com.stocks.contract.portfolio.UnitMath.unitValue(e2, flow.unitsAfter());
    BigDecimal twr = MetricsCalculator.twr(new UnitPoint(open.unitValue(), 1), new UnitPoint(u2, 1));
    BigDecimal expected = e1.divide(f0, 20, java.math.RoundingMode.HALF_EVEN).multiply(BigDecimal.ONE.add(r2))
        .subtract(BigDecimal.ONE);
    assertThat(twr.subtract(expected).abs()).isLessThan(new BigDecimal("0.0000001"));
  }
}
