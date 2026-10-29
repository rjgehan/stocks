package com.stocks.marketdata.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Daily indicators from a list of closes (oldest first, today's last): simple moving averages, Wilder RSI, returns
 * over n sessions and the 52-week range. Pure arithmetic; null when the list is too short.
 */
public final class Indicators {

  static final MathContext MC = MathContext.DECIMAL64;
  static final int PRICE_SCALE = 4;
  static final int RATIO_SCALE = 6;
  static final BigDecimal HUNDRED = new BigDecimal("100");

  private Indicators() {}

  /** Mean of the last {@code n} closes. */
  public static BigDecimal sma(List<BigDecimal> c, int n) {
    if (c.size() < n || n <= 0) {
      return null;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal x : c.subList(c.size() - n, c.size())) {
      sum = sum.add(x);
    }
    return sum.divide(BigDecimal.valueOf(n), MC).setScale(PRICE_SCALE, RoundingMode.HALF_EVEN);
  }

  /**
   * RSI(n) with Wilder smoothing over the whole list: the averages start at the first gain and loss, then
   * avg = avg_prev x (n - 1) / n + value / n; 100 when the average loss is 0. Needs at least n + 1 closes.
   */
  public static BigDecimal rsi(List<BigDecimal> c, int n) {
    if (c.size() < n + 1) {
      return null;
    }
    BigDecimal nn = BigDecimal.valueOf(n);
    BigDecimal keep = BigDecimal.valueOf(n - 1L);
    BigDecimal gain = BigDecimal.ZERO;
    BigDecimal loss = BigDecimal.ZERO;
    for (int i = 1; i < c.size(); i++) {
      BigDecimal d = c.get(i).subtract(c.get(i - 1));
      BigDecimal g = d.signum() > 0 ? d : BigDecimal.ZERO;
      BigDecimal l = d.signum() < 0 ? d.negate() : BigDecimal.ZERO;
      if (i == 1) {
        gain = g;
        loss = l;
      } else {
        gain = gain.multiply(keep).add(g).divide(nn, MC);
        loss = loss.multiply(keep).add(l).divide(nn, MC);
      }
    }
    if (loss.signum() == 0) {
      return HUNDRED.setScale(2, RoundingMode.HALF_EVEN);
    }
    BigDecimal rs = gain.divide(loss, MC);
    return HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(rs), MC)).setScale(2, RoundingMode.HALF_EVEN);
  }

  /** last / the close {@code n} sessions earlier - 1. */
  public static BigDecimal ret(List<BigDecimal> c, int n) {
    if (c.size() < n + 1) {
      return null;
    }
    BigDecimal then = c.get(c.size() - 1 - n);
    return then.signum() <= 0 ? null : ratioMinusOne(c.get(c.size() - 1), then);
  }

  /** a / b - 1, or null. */
  public static BigDecimal ratioMinusOne(BigDecimal a, BigDecimal b) {
    if (a == null || b == null || b.signum() <= 0) {
      return null;
    }
    return a.divide(b, MC).subtract(BigDecimal.ONE).setScale(RATIO_SCALE, RoundingMode.HALF_EVEN);
  }

  /** Highest (or lowest) of the last {@code n} closes (all of them when fewer). */
  public static BigDecimal extreme(List<BigDecimal> c, int n, boolean high) {
    if (c.isEmpty()) {
      return null;
    }
    BigDecimal out = null;
    for (BigDecimal x : c.subList(Math.max(0, c.size() - n), c.size())) {
      if (out == null || (high ? x.compareTo(out) > 0 : x.compareTo(out) < 0)) {
        out = x;
      }
    }
    return out.setScale(PRICE_SCALE, RoundingMode.HALF_EVEN);
  }
}
