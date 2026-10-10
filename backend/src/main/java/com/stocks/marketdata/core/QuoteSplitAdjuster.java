package com.stocks.marketdata.core;

import com.stocks.common.domain.SessionType;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.ExtendedLine;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.SplitAdjustments;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * §14 "Reference prices after a split" for quotes ({@link QuoteView}'s contract: {@code prevClose} is split-adjusted).
 * From an applied split's ex-date on, the prices of a quote that are dated before it are adjusted with
 * {@link SplitAdjustments#adjust}, so the quote page, watchlists, movers and the bots' {@code get_quotes} never show a
 * 2-for-1 split as a 50% drop: {@code prevClose}, a previous day's close or print shown as {@code last}, the previous
 * day's open/high/low (shown in the pre-market) and an extended-hours price from before the ex-date. {@code change},
 * {@code changePct} and the extended line's change are then recomputed exactly as {@link QuoteComposer} computes them.
 * Bid, ask and volume are left alone. Without an applied split in range the view is returned unchanged.
 */
public final class QuoteSplitAdjuster {

  private final SplitAdjustments splits;
  private final MarketCalendar calendar;
  private final TimeSource time;

  public QuoteSplitAdjuster(SplitAdjustments splits, MarketCalendar calendar, TimeSource time) {
    this.splits = splits;
    this.calendar = calendar;
    this.time = time;
  }

  public QuoteView adjust(QuoteView v) {
    if (v == null || v.symbol() == null || splits == null) {
      return v;
    }
    Instant now = time.now();
    LocalDate today = calendar.currentPnlDay(now);
    // the same days QuoteComposer reads: in the pre-market the "main" session is the previous trading day
    LocalDate mainDay = calendar.stateAt(now).type() == SessionType.PRE ? calendar.previousTradingDay(today) : today;
    LocalDate prevDay = calendar.previousTradingDay(mainDay);
    String s = v.symbol();
    BigDecimal prevClose = splits.adjust(s, v.prevClose(), prevDay, today);
    BigDecimal last = v.lastAt() == null ? v.last() : splits.adjust(s, v.last(), Et.date(v.lastAt()), today);
    BigDecimal open = splits.adjust(s, v.dayOpen(), mainDay, today);
    BigDecimal high = splits.adjust(s, v.dayHigh(), mainDay, today);
    BigDecimal low = splits.adjust(s, v.dayLow(), mainDay, today);
    ExtendedLine ext = v.extended();
    BigDecimal extPrice = ext == null || ext.at() == null ? (ext == null ? null : ext.price())
        : splits.adjust(s, ext.price(), Et.date(ext.at()), today);
    boolean changed = prevClose != v.prevClose() || last != v.last() || open != v.dayOpen() || high != v.dayHigh()
        || low != v.dayLow() || (ext != null && extPrice != ext.price());
    if (!changed) {
      return v;
    }
    BigDecimal change = last != null && prevClose != null ? last.subtract(prevClose) : null;
    BigDecimal changePct = QuoteComposer.pct(change, prevClose);
    ExtendedLine extended = ext;
    if (ext != null) {
      // QuoteComposer measures the extended line from the main session's close, which is `last` outside REGULAR
      BigDecimal extChange = extPrice != null && last != null ? extPrice.subtract(last) : null;
      extended = new ExtendedLine(ext.session(), extPrice, extChange, QuoteComposer.pct(extChange, last), ext.at());
    }
    return new QuoteView(v.symbol(), v.bid(), v.bidSize(), v.ask(), v.askSize(), last, v.lastSize(), v.lastAt(),
        prevClose, change, changePct, open, high, low, v.volume(), v.volumeIsPartial(), extended, v.asOf(),
        v.confirmedAt(), v.quality(), v.source(), v.halted());
  }
}
