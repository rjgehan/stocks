package com.stocks.marketdata.core;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.common.domain.SessionType;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.marketdata.MarkBasis;
import com.stocks.contract.marketdata.SplitAdjustments;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Live marks for equity (§15.5 {@code MarkService.mark}).
 *
 * <table>
 *   <caption>Preference order</caption>
 *   <tr><th>Session</th><th>Mark</th></tr>
 *   <tr><td>PRE, REGULAR, POST</td><td>1. consolidated eligible last ≤ 60 s · 2. fresh two-sided quote mid ·
 *       3. last trade of the current trading day · 4. official prior close · 5. last {@code day_end_mark}</td></tr>
 *   <tr><td>CLOSED</td><td>1. {@code day_end_mark} of the last trading day · 2. last trade</td></tr>
 * </table>
 * Fallbacks dated before an applied split's ex-date are split-adjusted (§14). A symbol under a corporate-action hold
 * is marked at its last {@code day_end_mark} dated before the earliest unresolved split's ex-date, unadjusted
 * (consistent with the unadjusted quantities, however long the hold lasts), quality {@code STALE}. SIM marks are the last
 * SIM print, split-adjusted like the fallbacks, quality {@code SIMULATED}.
 */
public class MarkService {

  static final Duration CONSOLIDATED_MAX_AGE = Duration.ofSeconds(60);
  static final Duration LAST_TRADE_FRESH = Duration.ofMinutes(15);

  private final MarketFeed feed;
  private final MarketCalendar calendar;
  private final TimeSource time;
  private final DailyMarkRepository dailyMarks;
  private final SplitAdjustments splits;
  /** The earliest ex-date of a held symbol's unresolved splits; null when it is not on hold. */
  private final Function<String, LocalDate> holdExDate;
  private final Duration quoteFreshRegular;
  private final Duration quoteFreshExt;

  public MarkService(MarketFeed feed, MarketCalendar calendar, TimeSource time, DailyMarkRepository dailyMarks,
                     SplitAdjustments splits, Function<String, LocalDate> holdExDate, Duration quoteFreshRegular,
                     Duration quoteFreshExt) {
    this.feed = feed;
    this.calendar = calendar;
    this.time = time;
    this.dailyMarks = dailyMarks;
    this.splits = splits;
    this.holdExDate = holdExDate;
    this.quoteFreshRegular = quoteFreshRegular;
    this.quoteFreshExt = quoteFreshExt;
  }

  public Mark mark(String symbol) {
    // a crypto pair (§29) is always in its REGULAR session: live marks on weekends and nights too
    MarketCalendar calendar = this.calendar.forSymbol(symbol);
    Instant now = time.now();
    LocalDate today = Et.date(now);
    LocalDate heldEx = holdExDate.apply(symbol);
    if (heldEx != null) {
      // the last pre-ex-date mark, even when the hold outlasts the ex-date (the ex-date's own close is post-split)
      LocalDate before = heldEx.isAfter(today) ? today : heldEx;
      return safeLastDayEnd(symbol, before.minusDays(1))
          .map(d -> new Mark(d.mark(), DataQuality.STALE, calendar.day(d.tradeDate()).map(TradingDay::postCloseAt)
              .orElse(null), MarkBasis.DAY_END))
          .orElse(new Mark(null, DataQuality.NONE, null, null));
    }
    MarketFeed.MarkInputs in = feed.markInputs(symbol);
    if (OptionSymbol.isOption(symbol)) {
      return optionMark(symbol, in, calendar, now);
    }
    if (in.simulated()) {
      Trade t = in.lastTrade();
      if (t != null) {
        // a SIM print from before an applied split's ex-date (03:45 until the first post-split print) is raw (§14)
        return new Mark(splits.adjust(symbol, t.price(), Et.date(t.ts()), today), DataQuality.SIMULATED, t.ts(),
            MarkBasis.SIMULATED);
      }
      return new Mark(null, DataQuality.NONE, null, null);
    }
    SessionType session = calendar.stateAt(now).type();
    LocalDate pnlDay = calendar.currentPnlDay(now);
    if (session == SessionType.CLOSED) {
      Optional<DailyMarkRepository.DayEnd> de = safeLastDayEnd(symbol, pnlDay);
      if (de.isPresent() && de.get().tradeDate().equals(pnlDay)) {
        return dayEnd(symbol, de.get(), today, DataQuality.DELAYED_15M);
      }
      Trade t = in.lastTrade();
      if (t != null) {
        LocalDate d = Et.date(t.ts());
        return new Mark(splits.adjust(symbol, t.price(), d, today), d.equals(pnlDay) ? DataQuality.DELAYED_15M
            : DataQuality.STALE, t.ts(), t.consolidated() ? MarkBasis.CONSOLIDATED_LAST : MarkBasis.IEX_LAST);
      }
      return de.map(d -> dayEnd(symbol, d, today, DataQuality.STALE)).orElse(new Mark(null, DataQuality.NONE, null,
          null));
    }
    // 1. consolidated eligible last ≤ 60 s old
    Trade c = in.lastConsolidated();
    if (c != null && c.consolidated() && c.eligible() && c.observedAt() != null
        && Duration.between(c.observedAt(), now).compareTo(CONSOLIDATED_MAX_AGE) <= 0) {
      return new Mark(c.price(), DataQuality.REALTIME_CONSOLIDATED, c.ts(), MarkBasis.CONSOLIDATED_LAST);
    }
    // 2. fresh two-sided quote mid
    Quote q = in.quote();
    Duration fresh = session == SessionType.REGULAR ? quoteFreshRegular : quoteFreshExt;
    if (q != null && q.twoSided() && in.quoteConfirmedAt() != null
        && Duration.between(in.quoteConfirmedAt(), now).compareTo(fresh) <= 0) {
      BigDecimal mid = q.bid().add(q.ask()).divide(BigDecimal.TWO, 4, RoundingMode.HALF_EVEN);
      return new Mark(mid, q.quality() == DataQuality.REALTIME_CONSOLIDATED ? DataQuality.REALTIME_CONSOLIDATED
          : DataQuality.REALTIME_SINGLE_VENUE, q.ts(), MarkBasis.IEX_MID);
    }
    // 3. last trade of the current trading day
    Trade t = in.lastTrade();
    Optional<TradingDay> day = calendar.day(pnlDay);
    if (t != null && day.isPresent() && !t.ts().isBefore(day.get().preOpenAt())) {
      boolean recent = Duration.between(t.ts(), now).compareTo(LAST_TRADE_FRESH) <= 0;
      return new Mark(t.price(), recent ? DataQuality.REALTIME_SINGLE_VENUE : DataQuality.STALE, t.ts(),
          MarkBasis.IEX_LAST);
    }
    // 4. official prior close
    LocalDate prev = calendar.previousTradingDay(pnlDay);
    Map<LocalDate, BigDecimal> closes = in.closes();
    BigDecimal prevClose = closes.get(prev);
    if (prevClose == null) {
      prevClose = safeOfficialClose(symbol, prev).orElse(null);
    }
    if (prevClose != null) {
      return new Mark(splits.adjust(symbol, prevClose, prev, today), DataQuality.STALE,
          day.map(TradingDay::preOpenAt).orElse(null), MarkBasis.PREV_CLOSE);
    }
    // 5. last day-end mark
    return safeLastDayEnd(symbol, today).map(d -> dayEnd(symbol, d, today, DataQuality.STALE))
        .orElse(new Mark(null, DataQuality.NONE, null, null));
  }

  /**
   * §30: an option is marked at its quote mid, or its bid when there is no ask (an ask without a bid counts the bid as
   * zero), and its last trade is never used (the indicative feed's trades are 15 minutes late). In the regular
   * session a confirmed quote is live; outside it, or with a stale quote, the day's {@code day_end_mark} wins once
   * it exists, then the latest quote (STALE), then the last day-end mark.
   */
  private Mark optionMark(String symbol, MarketFeed.MarkInputs in, MarketCalendar calendar, Instant now) {
    LocalDate pnlDay = calendar.currentPnlDay(now);
    SessionType session = calendar.stateAt(now).type();
    Quote q = in.quote();
    BigDecimal mid = optionMid(q);
    boolean confirmed = in.quoteConfirmedAt() != null
        && Duration.between(in.quoteConfirmedAt(), now).compareTo(quoteFreshRegular) <= 0;
    if (mid != null && session == SessionType.REGULAR && (confirmed || in.simulated())) {
      DataQuality quality = in.simulated() ? DataQuality.SIMULATED
          : q.quality() == null ? DataQuality.REALTIME_CONSOLIDATED : q.quality();
      return new Mark(mid, quality, q.ts(), MarkBasis.OPTION_MID);
    }
    Optional<DailyMarkRepository.DayEnd> de = safeLastDayEnd(symbol, pnlDay);
    if (de.isPresent() && de.get().tradeDate().equals(pnlDay)) {
      return new Mark(de.get().mark(), DataQuality.DELAYED_15M,
          calendar.day(de.get().tradeDate()).map(TradingDay::rthCloseAt).orElse(null), MarkBasis.DAY_END);
    }
    if (mid != null) {
      return new Mark(mid, in.simulated() ? DataQuality.SIMULATED : DataQuality.STALE, q.ts(), MarkBasis.OPTION_MID);
    }
    return de.map(d -> new Mark(d.mark(), DataQuality.STALE, calendar.day(d.tradeDate())
            .map(TradingDay::rthCloseAt).orElse(null), MarkBasis.DAY_END))
        .orElse(new Mark(null, DataQuality.NONE, null, null));
  }

  /** The option mark of a quote: mid, the bid without an ask, half the ask without a bid; null without prices. */
  public static BigDecimal optionMid(Quote q) {
    if (q == null) {
      return null;
    }
    BigDecimal bid = q.bid() != null && q.bid().signum() > 0 ? q.bid() : null;
    BigDecimal ask = q.ask() != null && q.ask().signum() > 0 ? q.ask() : null;
    if (bid != null && ask != null && ask.compareTo(bid) >= 0) {
      return bid.add(ask).divide(BigDecimal.TWO, 4, RoundingMode.HALF_EVEN);
    }
    if (bid != null) {
      return bid.setScale(4, RoundingMode.HALF_EVEN);
    }
    return ask == null ? null : ask.divide(BigDecimal.TWO, 4, RoundingMode.HALF_EVEN);
  }

  private Mark dayEnd(String symbol, DailyMarkRepository.DayEnd d, LocalDate today, DataQuality quality) {
    Instant at = calendar.forSymbol(symbol).day(d.tradeDate()).map(TradingDay::postCloseAt).orElse(null);
    return new Mark(splits.adjust(symbol, d.mark(), d.tradeDate(), today), quality, at, MarkBasis.DAY_END);
  }

  private Optional<DailyMarkRepository.DayEnd> safeLastDayEnd(String symbol, LocalDate onOrBefore) {
    try {
      return dailyMarks.lastDayEnd(symbol, onOrBefore);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private Optional<BigDecimal> safeOfficialClose(String symbol, LocalDate d) {
    try {
      return dailyMarks.officialClose(symbol, d);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
