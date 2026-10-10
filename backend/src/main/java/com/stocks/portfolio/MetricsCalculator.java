package com.stocks.portfolio;

import java.math.BigDecimal;
import java.math.MathContext;
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

  /**
   * §16.8 risk metrics over a window's daily returns: how many there were, and the annualized Sharpe ratio (scale 4)
   * and volatility (scale 10), both null below {@link #SHARPE_MIN_DAYS} returns or with no dispersion.
   */
  public record RiskStats(int dailyReturns, BigDecimal sharpe, BigDecimal volatility) {
    public static final RiskStats NONE = new RiskStats(0, null, null);
  }

  /** §16.8: Sharpe and volatility need at least this many daily returns. */
  public static final int SHARPE_MIN_DAYS = 20;
  /** Sharpe ratio and profit factor: scale 4. */
  public static final int RATIO_SCALE = 4;
  /** Trading days per year (annualization). */
  public static final int TRADING_DAYS = 252;
  private static final MathContext MC = MathContext.DECIMAL128;
  private static final BigDecimal SQRT_TRADING_DAYS = BigDecimal.valueOf(TRADING_DAYS).sqrt(MC);

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

  /**
   * §16.8 daily returns of a path (the window's anchor, then one EOD unit value per trading day, oldest first; never
   * the live end): {@code r_i = U_i / U_{i−1} − 1} at {@link MathContext#DECIMAL128}; −1 when the unit series changes
   * (a rebase, §16.2), 0 after a wiped-out day (U = 0 cannot move without a flow, and a flow rebases).
   */
  public static List<BigDecimal> dailyReturns(List<UnitPoint> path) {
    List<BigDecimal> out = new ArrayList<>();
    for (int i = 1; i < path.size(); i++) {
      UnitPoint prev = path.get(i - 1);
      UnitPoint cur = path.get(i);
      if (prev.unitSeries() != cur.unitSeries()) {
        out.add(BigDecimal.ONE.negate());
      } else if (prev.unitValue().signum() <= 0) {
        out.add(BigDecimal.ZERO);
      } else {
        out.add(cur.unitValue().divide(prev.unitValue(), MC).subtract(BigDecimal.ONE, MC));
      }
    }
    return out;
  }

  /**
   * §16.8 Sharpe ratio (risk-free rate 0) and volatility from daily returns: {@code mean / sd × √252} and
   * {@code sd × √252}, with the sample standard deviation ({@code n − 1}); null below {@link #SHARPE_MIN_DAYS} returns
   * or when {@code sd = 0}.
   */
  public static RiskStats risk(List<BigDecimal> dailyReturns) {
    int n = dailyReturns.size();
    if (n < SHARPE_MIN_DAYS) {
      return new RiskStats(n, null, null);
    }
    BigDecimal count = BigDecimal.valueOf(n);
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal r : dailyReturns) {
      sum = sum.add(r, MC);
    }
    BigDecimal mean = sum.divide(count, MC);
    BigDecimal squares = BigDecimal.ZERO;
    for (BigDecimal r : dailyReturns) {
      BigDecimal d = r.subtract(mean, MC);
      squares = squares.add(d.multiply(d, MC), MC);
    }
    BigDecimal variance = squares.divide(BigDecimal.valueOf(n - 1L), MC);
    if (variance.signum() <= 0) {
      return new RiskStats(n, null, null);
    }
    BigDecimal sd = variance.sqrt(MC);
    BigDecimal sharpe = mean.divide(sd, MC).multiply(SQRT_TRADING_DAYS, MC).setScale(RATIO_SCALE, MODE);
    BigDecimal volatility = sd.multiply(SQRT_TRADING_DAYS, MC).setScale(RETURN_SCALE, MODE);
    return new RiskStats(n, sharpe, volatility);
  }

  /** {@link #risk(List)} of {@link #dailyReturns(List)}. */
  public static RiskStats riskOfPath(List<UnitPoint> path) {
    return risk(dailyReturns(path));
  }

  /**
   * §16.8 profit factor {@code grossProfit / grossLoss} at scale 4 (both positive sums of realized P&amp;L); null when
   * there is no loss (the SPA shows ∞ when there is a profit).
   */
  public static BigDecimal profitFactor(BigDecimal grossProfit, BigDecimal grossLoss) {
    if (grossProfit == null || grossLoss == null || grossLoss.signum() <= 0) {
      return null;
    }
    return grossProfit.divide(grossLoss, RATIO_SCALE, MODE);
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
