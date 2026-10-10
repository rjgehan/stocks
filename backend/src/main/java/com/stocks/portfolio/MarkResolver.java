package com.stocks.portfolio;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.money.Prices;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.marketdata.MarkBasis;
import com.stocks.contract.marketdata.MarketDataService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Marks for valuation (§16.1). Live marks come from WS2's {@code MarkService} through
 * {@link MarketDataService#mark}, which already walks its own fallbacks (consolidated last, IEX mid, last trade, prior
 * close, day-end mark; split-adjusted). Only when it knows <b>no price at all</b> (quality {@code NONE}) does this
 * class fall back further, so an account is never valued at zero for lack of data: the symbol's most recent fill in
 * the league (quality {@code STALE}), then the position's average cost (quality {@code NONE}). Either way the
 * valuation reports the degraded quality. A symbol that a NAME_CHANGE created has no price of its own until it trades
 * (§14): until then the former symbol's mark and fills value it, so a rename is never a gain or a loss.
 *
 * <p>End-of-day marks (§16.4) prefer {@code dayEndMark(symbol, D)} (raw for D).
 */
@Component
public class MarkResolver {

  private static final Logger log = LoggerFactory.getLogger(MarkResolver.class);

  /** After the cut-off and before the next pre-open (8 h later at most), the live mark is the day-end price. */
  static final Duration LIVE_AFTER_CUTOFF = Duration.ofHours(8);

  private final MarketDataService marketData;
  private final PortfolioRepository repository;
  private final TimeSource time;

  public MarkResolver(MarketDataService marketData, PortfolioRepository repository, TimeSource time) {
    this.marketData = marketData;
    this.repository = repository;
    this.time = time;
  }

  /**
   * The live mark of {@code symbol}; never has a null price.
   *
   * @param qty       the position's quantity (for the average-cost fallback; may be null)
   * @param costBasis the position's cost basis (may be null)
   */
  public Mark live(String symbol, BigDecimal qty, BigDecimal costBasis) {
    Mark m = null;
    try {
      m = marketData.mark(symbol);
    } catch (RuntimeException e) {
      log.warn("mark({}) failed: {}", symbol, e.toString());
    }
    if (m != null && m.price() != null && m.price().signum() > 0) {
      return m;
    }
    for (String former : formerSymbols(symbol)) {
      try {
        Mark f = marketData.mark(former);
        if (f != null && f.price() != null && f.price().signum() > 0) {
          return f;
        }
      } catch (RuntimeException e) {
        log.warn("mark({}) failed: {}", former, e.toString());
      }
    }
    return fallback(symbol, time.now(), qty, costBasis);
  }

  /**
   * The end-of-day mark of {@code symbol} for trading day {@code date} at cut-off {@code cutoff} (§16.4): WS2's
   * {@code dayEndMark}; else, when the cut-off has just passed (the normal 20:20 run), the live mark; else the last
   * fill at or before the cut-off; else the average cost.
   */
  public Mark endOfDay(String symbol, LocalDate date, Instant cutoff, BigDecimal qty, BigDecimal costBasis) {
    try {
      Optional<BigDecimal> dayEnd = marketData.dayEndMark(symbol, date);
      if (dayEnd.isPresent() && dayEnd.get().signum() > 0) {
        return new Mark(dayEnd.get(), DataQuality.REALTIME_CONSOLIDATED, cutoff, MarkBasis.DAY_END);
      }
    } catch (RuntimeException e) {
      log.warn("dayEndMark({}, {}) failed: {}", symbol, date, e.toString());
    }
    Instant now = time.now();
    boolean justClosed = !now.isBefore(cutoff) && now.isBefore(cutoff.plus(LIVE_AFTER_CUTOFF));
    if (justClosed) {
      Mark live = live(symbol, qty, costBasis);
      if (live.quality() != DataQuality.NONE) {
        return live;
      }
    }
    return fallback(symbol, cutoff, qty, costBasis);
  }

  /**
   * The EOD mark of a holding that is still pre-split at the cut-off (§14 corporate-action hold): the first day-end
   * mark found on {@code daysBeforeEx} (trading days before the split's ex-date, newest first), unadjusted, quality
   * {@code STALE}, like the live hold mark; else the last fill before the ex-date's pre-open, else the average cost.
   * The day's own day-end mark is post-split and would value the unadjusted quantity at a fraction of its worth.
   */
  public Mark heldEndOfDay(String symbol, List<LocalDate> daysBeforeEx, Instant exPreOpen, Instant cutoff,
                           BigDecimal qty, BigDecimal costBasis) {
    for (LocalDate d : daysBeforeEx) {
      try {
        Optional<BigDecimal> dayEnd = marketData.dayEndMark(symbol, d);
        if (dayEnd.isPresent() && dayEnd.get().signum() > 0) {
          return new Mark(dayEnd.get(), DataQuality.STALE, cutoff, MarkBasis.DAY_END);
        }
      } catch (RuntimeException e) {
        log.warn("dayEndMark({}, {}) failed: {}", symbol, d, e.toString());
      }
    }
    Mark f = fallback(symbol, exPreOpen, qty, costBasis);
    return f.quality() == DataQuality.NONE ? f : new Mark(f.price(), DataQuality.STALE, cutoff, f.basis());
  }

  private List<String> formerSymbols(String symbol) {
    try {
      return repository.formerSymbols(symbol);
    } catch (RuntimeException e) {
      log.warn("formerSymbols({}) failed: {}", symbol, e.toString());
      return List.of();
    }
  }

  private Mark fallback(String symbol, Instant at, BigDecimal qty, BigDecimal costBasis) {
    Optional<BigDecimal> lastFill = repository.lastFillPrice(symbol, at);
    for (String former : lastFill.isPresent() ? List.<String>of() : formerSymbols(symbol)) {
      lastFill = repository.lastFillPrice(former, at);
      if (lastFill.isPresent()) {
        break;
      }
    }
    if (lastFill.isPresent()) {
      return new Mark(lastFill.get(), DataQuality.STALE, at, null);
    }
    if (qty != null && qty.signum() > 0 && costBasis != null) {
      BigDecimal avg = costBasis.divide(qty, Prices.SCALE, RoundingMode.HALF_EVEN);
      return new Mark(avg, DataQuality.NONE, at, null);
    }
    return new Mark(BigDecimal.ZERO.setScale(Prices.SCALE), DataQuality.NONE, at, null);
  }
}
