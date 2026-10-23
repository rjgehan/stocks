package com.stocks.marketdata.core;

import com.stocks.common.domain.Crypto;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects splits the corporate-action feed missed (§14, §15.5): for every held symbol it compares the raw and the
 * split-adjusted daily bars of the last few trading days. When they differ and no split is applied
 * ({@code price_adjustment}) on the inferred ex-date, it asks WS3's {@code CorporateActionService.flagDetectedSplit}
 * to put the symbol on hold for the admin. The ratio is inferred as the closest small fraction ({@code p/q}, both
 * ≤ 20). It runs after {@code PrevCloseJob} (04:01 ET) and again at 09:20 ET.
 */
public class SplitGuard {

  private static final Logger log = LoggerFactory.getLogger(SplitGuard.class);
  static final int LOOKBACK_DAYS = 5;
  static final BigDecimal TOLERANCE = new BigDecimal("0.005");

  /** A detected split: {@code oldRate → newRate} effective on {@code exDate}. */
  public record Detected(String symbol, LocalDate exDate, BigDecimal oldRate, BigDecimal newRate) {}

  /** Flags a detection (WS3); may throw. */
  public interface Flagger {
    void flag(Detected d);
  }

  private final MarketDataProvider provider;
  private final String feed;
  private final TrackedSymbols tracked;
  private final SplitAdjustmentsImpl applied;
  private final Flagger flagger;
  private final MarketCalendar calendar;
  private final TimeSource time;

  public SplitGuard(MarketDataProvider provider, String feed, TrackedSymbols tracked, SplitAdjustmentsImpl applied,
                    Flagger flagger, MarketCalendar calendar, TimeSource time) {
    this.provider = provider;
    this.feed = feed;
    this.tracked = tracked;
    this.applied = applied;
    this.flagger = flagger;
    this.calendar = calendar;
    this.time = time;
  }

  /** Checks every held symbol; returns what it flagged. */
  public List<Detected> run() {
    Set<String> held = new java.util.TreeSet<>(tracked.held());
    held.removeIf(s -> Crypto.isCrypto(s) || OptionSymbol.isOption(s));   // coins and contracts (§29, §30)
    if (held.isEmpty()) {
      return List.of();
    }
    LocalDate today = Et.date(time.now());
    LocalDate start = today;
    for (int i = 0; i < LOOKBACK_DAYS; i++) {
      start = calendar.previousTradingDay(start);
    }
    Map<String, List<Bar>> raw;
    Map<String, List<Bar>> adjusted;
    try {
      BarQuery q = new BarQuery(held, Timeframe.DAY1, Et.startOfDay(start), Et.startOfDay(today.plusDays(1)),
          AdjustmentMode.RAW, false, 10_000, feed);
      raw = provider.bars(q, RefreshPriority.BACKGROUND);
      adjusted = provider.bars(new BarQuery(held, Timeframe.DAY1, q.from(), q.to(), AdjustmentMode.SPLIT, false,
          10_000, feed), RefreshPriority.BACKGROUND);
    } catch (ProviderException | UnsupportedOperationException e) {
      log.warn("SplitGuard: bars unavailable: {}", e.getMessage());
      return List.of();
    }
    List<Detected> out = new ArrayList<>();
    for (String s : held) {
      Optional<Detected> d = detect(s, raw.getOrDefault(s, List.of()), adjusted.getOrDefault(s, List.of()));
      if (d.isEmpty() || applied != null && applied.appliedOn(s, d.get().exDate())) {
        continue;
      }
      try {
        flagger.flag(d.get());
        out.add(d.get());
        log.warn("SplitGuard: {} looks split {}→{} on {}; flagged for the admin", s, d.get().oldRate(),
            d.get().newRate(), d.get().exDate());
      } catch (RuntimeException e) {
        log.warn("SplitGuard: could not flag {} ({}): {}", s, d.get(), e.getMessage());
      }
    }
    return out;
  }

  /**
   * Raw vs split-adjusted daily bars of one symbol: the ex-date is the first date after a run of bars whose ratio
   * {@code raw/adjusted} differs from 1; the rates come from that ratio.
   */
  static Optional<Detected> detect(String symbol, List<Bar> raw, List<Bar> adjusted) {
    Map<LocalDate, BigDecimal> adj = new HashMap<>();
    adjusted.forEach(b -> adj.put(Et.date(b.start()), b.close()));
    BigDecimal ratio = null;
    LocalDate exDate = null;
    for (Bar b : raw) {
      LocalDate d = Et.date(b.start());
      BigDecimal a = adj.get(d);
      if (a == null || a.signum() == 0 || b.close() == null) {
        continue;
      }
      BigDecimal r = b.close().divide(a, 8, RoundingMode.HALF_EVEN);
      boolean one = r.subtract(BigDecimal.ONE).abs().compareTo(TOLERANCE) <= 0;
      if (!one) {
        ratio = r;
        exDate = null;
      } else if (ratio != null && exDate == null) {
        exDate = d;
      }
    }
    if (ratio == null || exDate == null) {
      return Optional.empty();
    }
    // raw/adjusted = new/old for a forward split (2-for-1: raw prices before the ex-date are twice the adjusted)
    BigDecimal[] frac = fraction(ratio);
    return frac == null ? Optional.empty() : Optional.of(new Detected(symbol, exDate, frac[1], frac[0]));
  }

  /** {@code p/q ≈ x} with {@code p, q ≤ 20}, within 0.5%; {@code {p, q}}. */
  static BigDecimal[] fraction(BigDecimal x) {
    BigDecimal best = null;
    BigDecimal[] out = null;
    for (int q = 1; q <= 20; q++) {
      for (int p = 1; p <= 20; p++) {
        BigDecimal v = BigDecimal.valueOf(p).divide(BigDecimal.valueOf(q), 8, RoundingMode.HALF_EVEN);
        BigDecimal err = v.subtract(x).abs().divide(x, 8, RoundingMode.HALF_EVEN);
        if (err.compareTo(TOLERANCE) <= 0 && (best == null || err.compareTo(best) < 0)) {
          best = err;
          out = new BigDecimal[] {BigDecimal.valueOf(p), BigDecimal.valueOf(q)};
        }
      }
    }
    return out;
  }
}
