package com.stocks.marketdata.alpaca;

import java.util.Arrays;

/**
 * A ring buffer of recent positive quote sizes and the unit check of §15.8: Alpaca documents stream quote sizes in
 * round lots while REST sizes are shares (since 2025-11-03), and either may change, so the live feed compares the
 * medians of both and picks the stream multiplier (100 when the stream is ~100× smaller, else 1).
 */
public final class SizeSample {

  /** Samples needed on each side before deciding. */
  public static final int MIN_SAMPLES = 40;

  private final long[] values;
  private int next;
  private int count;

  public SizeSample(int capacity) {
    this.values = new long[capacity];
  }

  public synchronized void add(long size) {
    if (size <= 0) {
      return;
    }
    values[next] = size;
    next = (next + 1) % values.length;
    count = Math.min(count + 1, values.length);
  }

  public synchronized int count() {
    return count;
  }

  /** The median, or 0 when empty. */
  public synchronized long median() {
    if (count == 0) {
      return 0;
    }
    long[] copy = Arrays.copyOf(values, count);
    Arrays.sort(copy);
    return copy[count / 2];
  }

  /**
   * The stream size multiplier implied by {@code rest} (shares) and {@code stream} (raw) samples, or 0 when there is
   * not enough data yet.
   */
  public static long multiplier(SizeSample rest, SizeSample stream) {
    if (rest.count() < MIN_SAMPLES || stream.count() < MIN_SAMPLES) {
      return 0;
    }
    long r = rest.median();
    long s = Math.max(1, stream.median());
    return r >= 20 * s ? 100 : 1;
  }
}
