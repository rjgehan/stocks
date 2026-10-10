package com.stocks.marketdata.core;

import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.Et;
import com.stocks.contract.marketdata.model.Bar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bar arithmetic: aggregation to coarser timeframes and merging (pure). */
public final class BarMath {

  private BarMath() {}

  /** Length of an intraday timeframe; null for 1Day and coarser. */
  public static Duration length(Timeframe tf) {
    return switch (tf) {
      case MIN1 -> Duration.ofMinutes(1);
      case MIN5 -> Duration.ofMinutes(5);
      case MIN15 -> Duration.ofMinutes(15);
      case MIN30 -> Duration.ofMinutes(30);
      case HOUR1 -> Duration.ofHours(1);
      default -> null;
    };
  }

  public static boolean intraday(Timeframe tf) {
    return length(tf) != null;
  }

  /**
   * Aggregates finer bars (sorted by start) into {@code tf}: intraday buckets aligned to multiples of the length since
   * the epoch (Alpaca's alignment), weeks by ISO week (Monday, ET dates) and months by ET calendar month. The bucket's
   * start is the first input bar's start for weeks and months.
   */
  public static List<Bar> aggregate(List<Bar> bars, Timeframe tf) {
    Map<Object, List<Bar>> buckets = new LinkedHashMap<>();
    Duration len = length(tf);
    for (Bar b : bars) {
      Object key;
      if (len != null) {
        long s = len.getSeconds();
        key = Instant.ofEpochSecond(Math.floorDiv(b.start().getEpochSecond(), s) * s);
      } else if (tf == Timeframe.WEEK1) {
        key = Et.date(b.start()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      } else if (tf == Timeframe.MONTH1) {
        LocalDate d = Et.date(b.start());
        key = d.withDayOfMonth(1);
      } else {
        key = Et.date(b.start());
      }
      buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
    }
    List<Bar> out = new ArrayList<>(buckets.size());
    buckets.forEach((key, group) -> out.add(merge(group, tf, key instanceof Instant i ? i : group.get(0).start())));
    return out;
  }

  /** One bar from consecutive bars. */
  public static Bar merge(List<Bar> group, Timeframe tf, Instant start) {
    Bar first = group.get(0);
    Bar last = group.get(group.size() - 1);
    BigDecimal high = first.high();
    BigDecimal low = first.low();
    long volume = 0;
    long count = 0;
    BigDecimal notional = BigDecimal.ZERO;
    boolean partial = false;
    for (Bar b : group) {
      high = high.max(b.high());
      low = low.min(b.low());
      volume += b.volume();
      count += b.tradeCount();
      partial |= b.partialVolume();
      BigDecimal px = b.vwap() != null ? b.vwap() : b.close();
      notional = notional.add(px.multiply(BigDecimal.valueOf(b.volume())));
    }
    BigDecimal vwap = volume > 0 ? notional.divide(BigDecimal.valueOf(volume), 4, RoundingMode.HALF_EVEN) : null;
    return new Bar(first.symbol(), tf, start, first.open(), high, low, last.close(), volume, vwap, count, partial);
  }

  /** Merges two sorted series by bar start; on equal starts {@code preferred} wins (SIP over the IEX tail). */
  public static List<Bar> mergeByStart(List<Bar> preferred, List<Bar> other) {
    Map<Instant, Bar> byStart = new java.util.TreeMap<>();
    other.forEach(b -> byStart.put(b.start(), b));
    preferred.forEach(b -> byStart.put(b.start(), b));
    return new ArrayList<>(byStart.values());
  }
}
