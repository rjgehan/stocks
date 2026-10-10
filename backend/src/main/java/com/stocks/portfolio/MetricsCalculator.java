package com.stocks.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Pure performance metrics (§16.2, §16.5, §16.6). Returns are fractions at scale 10 ({@code NUMERIC(20,10)},
 * {@code 0.0123} = +1.23%), HALF_EVEN; every division names its scale.
 */
public final class MetricsCalculator {

  /** Returns, drawdowns and win rates: scale 10. */
  public static final int RETURN_SCALE = 10;
  private static final RoundingMode MODE = RoundingMode.HALF_EVEN;
  private static final BigDecimal MINUS_ONE = BigDecimal.ONE.negate().setScale(RETURN_SCALE);
  private static final BigDecimal ONE = BigDecimal.ONE.setScale(RETURN_SCALE);
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(RETURN_SCALE);

  /** A unit value on a unit series (§16.2). */
  public record UnitPoint(BigDecimal unitValue, int unitSeries) {}

  /** An external flow at an instant (for the money-weighted return). */
  public record Flow(Instant at, BigDecimal amount) {}

  private MetricsCalculator() {}

  /**
   * §16.2 TWR: {@code U(b) / U(a) − 1} on one unit series; −1 (−100%) when the window spans a rebase (the series
   * differ), whatever happened afterwards.
   */
  public static BigDecimal twr(UnitPoint start, UnitPoint end) {
    if (start.unitSeries() != end.unitSeries()) {
      return MINUS_ONE;
    }
    return ratioMinusOne(end.unitValue(), start.unitValue());
  }

  /** {@code b / a − 1} at scale 10; null when {@code a} or {@code b} is null or {@code a} is not positive. */
  public static BigDecimal ratioMinusOne(BigDecimal b, BigDecimal a) {
    if (a == null || b == null || a.signum() <= 0) {
      return null;
    }
    return b.divide(a, RETURN_SCALE + 8, MODE).subtract(BigDecimal.ONE).setScale(RETURN_SCALE, MODE);
  }

  /** §16.6 {@code excess = TWR − (B_b / B_a − 1)}; null if the benchmark is unknown at either end. */
  public static BigDecimal excess(BigDecimal twr, BigDecimal benchStart, BigDecimal benchEnd) {
    BigDecimal bench = ratioMinusOne(benchEnd, benchStart);
    if (twr == null || bench == null) {
      return null;
    }
    return twr.subtract(bench).setScale(RETURN_SCALE, MODE);
  }

  /**
   * §16.5 max drawdown {@code max_t (1 − U_t / max_{s ≤ t} U_s)} over the points in time order, as a positive
   * fraction; 1 (100%) if they span a rebase; 0 for fewer than two points.
   */
  public static BigDecimal maxDrawdown(List<UnitPoint> points) {
    if (points.isEmpty()) {
      return ZERO;
    }
    int series = points.get(0).unitSeries();
    BigDecimal peak = null;
    BigDecimal worst = ZERO;
    for (UnitPoint p : points) {
      if (p.unitSeries() != series) {
        return ONE;
      }
      BigDecimal u = p.unitValue();
      if (peak == null || u.compareTo(peak) > 0) {
        peak = u;
      }
      if (peak.signum() > 0) {
        BigDecimal dd = BigDecimal.ONE.subtract(u.divide(peak, RETURN_SCALE + 8, MODE)).setScale(RETURN_SCALE, MODE);
        if (dd.compareTo(worst) > 0) {
          worst = dd;
        }
      }
    }
    return worst;
  }

  /** Wins ÷ closing sells at scale 10; null when there were no closing sells. */
  public static BigDecimal winRate(int wins, int closingSells) {
    if (closingSells <= 0) {
      return null;
    }
    return BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(closingSells), RETURN_SCALE, MODE);
  }

  /** The median (mean of the two middle values for an even count); null for none. */
  public static BigDecimal median(Collection<BigDecimal> values) {
    List<BigDecimal> sorted = new ArrayList<>(values);
    sorted.removeIf(v -> v == null);
    if (sorted.isEmpty()) {
      return null;
    }
    sorted.sort(null);
    int n = sorted.size();
    if (n % 2 == 1) {
      return sorted.get(n / 2).setScale(RETURN_SCALE, MODE);
    }
    return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.TWO, RETURN_SCALE, MODE);
  }

  /**
   * §16.2 money-weighted return (Modified Dietz, profile only, never for ranking):
   * {@code PnL / (E(a) + Σ w_i F_i)} with {@code w_i = (b − t_i) / (b − a)}; null when the denominator is not
   * positive or the window is empty.
   */
  public static BigDecimal moneyWeightedReturn(BigDecimal pnl, BigDecimal equityAtStart, List<Flow> flows,
                                               Instant a, Instant b) {
    long span = Duration.between(a, b).toMillis();
    if (span <= 0) {
      return null;
    }
    BigDecimal spanMs = BigDecimal.valueOf(span);
    BigDecimal denominator = equityAtStart;
    for (Flow f : flows) {
      // flows before a are already in E(a); a flow at a itself (an opening INITIAL) has weight 1
      if (!f.at().isBefore(a) && !f.at().isAfter(b)) {
        BigDecimal w = BigDecimal.valueOf(Duration.between(f.at(), b).toMillis()).divide(spanMs, 18, MODE);
        denominator = denominator.add(f.amount().multiply(w));
      }
    }
    if (denominator.signum() <= 0) {
      return null;
    }
    return pnl.divide(denominator, RETURN_SCALE, MODE);
  }
}
