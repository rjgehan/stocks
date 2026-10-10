package com.stocks.marketdata.sim;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic randomness for the SIM market (§15.7): every draw is a pure function of a 64-bit key, built by
 * {@link #mix} from the seed, the symbol and the time coordinates. Only {@link StrictMath} is used, so a value is
 * bit-for-bit identical on every JVM and CPU (CI on linux-amd64, laptops on darwin-arm64), and the golden tests hold.
 */
final class SimMath {

  static final double TWO_PI = 2 * StrictMath.PI;
  private static final double INV_2_53 = 1.0 / (1L << 53);

  private SimMath() {}

  /** SplitMix64's finalizer: a high-quality 64-bit mix. */
  static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }

  /** Combines key parts into one key (order matters). */
  static long mix(long... parts) {
    long h = 0x9E3779B97F4A7C15L;
    for (long p : parts) {
      h = mix64(h ^ p) + 0x9E3779B97F4A7C15L;
    }
    return mix64(h);
  }

  /** A stable 64-bit hash of a string (FNV-1a over UTF-8, then mixed). */
  static long hash(String s) {
    long h = 0xcbf29ce484222325L;
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      h ^= (b & 0xff);
      h *= 0x100000001b3L;
    }
    return mix64(h);
  }

  /** Uniform in [0, 1) from a key. */
  static double uniform(long key) {
    return (mix64(key) >>> 11) * INV_2_53;
  }

  /** Uniform in (0, 1] (safe for logarithms). */
  static double uniformOpen(long key) {
    return ((mix64(key) >>> 11) + 1) * INV_2_53;
  }

  /** Standard normal from a key (Box–Muller with two independent uniforms). */
  static double normal(long key) {
    double u1 = uniformOpen(key ^ 0x5DEECE66DL);
    double u2 = uniform(key + 0x2545F4914F6CDD1DL);
    return StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(TWO_PI * u2);
  }

  /** Poisson(λ) count from a key (inverse transform; λ is small here). */
  static int poisson(double lambda, long key) {
    if (lambda <= 0) {
      return 0;
    }
    double u = uniform(key);
    double p = StrictMath.exp(-lambda);
    double cdf = p;
    int k = 0;
    while (u > cdf && k < 64) {
      k++;
      p = p * lambda / k;
      cdf += p;
    }
    return k;
  }

  /**
   * A standard Brownian bridge on [0, 1] (zero at both ends) evaluated at {@code u}, built by dyadic midpoint
   * refinement to {@code depth} levels: the node at grid index {@code k} (of 2^depth) is a pure function of
   * {@code (key, k)}, so any point costs {@code depth} draws and no state. Between grid nodes it is linear.
   */
  static double bridge(long key, int depth, double u) {
    if (u <= 0 || u >= 1) {
      return 0;
    }
    long n = 1L << depth;
    double x = u * n;
    long k = (long) StrictMath.floor(x);
    double frac = x - k;
    double a = node(key, depth, k);
    double b = k + 1 >= n ? 0 : node(key, depth, k + 1);
    return a + (b - a) * frac;
  }

  /** The bridge value at grid index {@code k} in [0, 2^depth]. */
  static double node(long key, int depth, long k) {
    long n = 1L << depth;
    if (k <= 0 || k >= n) {
      return 0;
    }
    long lo = 0;
    long hi = n;
    double vlo = 0;
    double vhi = 0;
    while (true) {
      long mid = (lo + hi) >>> 1;
      double sd = StrictMath.sqrt((double) (hi - lo) / n / 4.0);
      double vmid = (vlo + vhi) / 2 + sd * normal(mix(key, mid));
      if (k == mid) {
        return vmid;
      }
      if (k < mid) {
        hi = mid;
        vhi = vmid;
      } else {
        lo = mid;
        vlo = vmid;
      }
    }
  }

  /**
   * Every grid node at refinement level {@code level} (≤ depth) of the same bridge, 2^level + 1 values, in one pass
   * (each node drawn once). Node {@code i} equals {@code node(key, depth, i << (depth - level))}.
   */
  static double[] level(long key, int depth, int level) {
    int count = 1 << level;
    double[] v = new double[count + 1];
    long n = 1L << depth;
    for (int step = count; step > 1; step >>= 1) {
      for (int lo = 0; lo + step <= count; lo += step) {
        int mid = lo + step / 2;
        long hiG = (long) (lo + step) << (depth - level);
        long loG = (long) lo << (depth - level);
        double sd = StrictMath.sqrt((double) (hiG - loG) / n / 4.0);
        long midG = (loG + hiG) >>> 1;
        v[mid] = (v[lo] + v[lo + step]) / 2 + sd * normal(mix(key, midG));
      }
    }
    return v;
  }

  /** A double rounded to 6 decimals, as an exact BigDecimal (never through BigDecimal.valueOf(double), rule 6). */
  static BigDecimal decimal6(double x) {
    return BigDecimal.valueOf(StrictMath.round(x * 1_000_000d), 6);
  }
}
