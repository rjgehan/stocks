package com.stocks.marketdata.core;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.ExtendedLine;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.marketdata.spi.PointInTimeMarket.DayStats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.function.Function;

/**
 * Composes the {@link QuoteView} the UI and bots see (§15.5), Webull style: the main line describes the regular
 * session and an extended line shows pre-market or after-hours trading.
 *
 * <ul>
 *   <li><b>PRE</b> on trading day T: main price = the prior regular close, change vs the close before it, day stats of
 *       that prior day; extended line = the latest pre-market print vs the prior close.</li>
 *   <li><b>REGULAR</b>: main price = the latest print, change vs the prior close.</li>
 *   <li><b>POST</b>, and CLOSED until the next pre-open: main price = T's regular close; extended line = the latest
 *       after-hours print vs that close.</li>
 * </ul>
 * Labels: {@code SIMULATED} for SIM; {@code REALTIME_SINGLE_VENUE} (IEX) or {@code REALTIME_CONSOLIDATED} (SIP) while
 * confirmed; {@code SYNTHETIC} when only a fresh consolidated last exists ("Est."); {@code STALE} when past the §12.3
 * freshness thresholds during venue hours; {@code NONE} when nothing is known. {@code changePct} is a fraction at
 * scale 10 ({@code 0.0123} = +1.23%).
 */
public final class QuoteComposer {

  /** Everything known about one symbol, provider-agnostic. */
  public record Inputs(String symbol, Quote quote, Instant confirmedAt, Trade lastTrade,
                       Function<LocalDate, Optional<BigDecimal>> closes,
                       Function<LocalDate, Optional<DayStats>> dayStats, boolean volumePartial, boolean halted,
                       boolean simulated, boolean consolidatedQuote, String source) {}

  /** Venue hours of the live quote feed (IEX 08:00–17:00 ET); null = the whole session (SIM, SIP). */
  public record Venue(LocalTime open, LocalTime close) {}

  private final Duration regularThreshold;
  private final Duration extThreshold;
  private static final Duration CONSOLIDATED_FRESH = Duration.ofSeconds(60);

  public QuoteComposer(Duration regularThreshold, Duration extThreshold) {
    this.regularThreshold = regularThreshold;
    this.extThreshold = extThreshold;
  }

  public QuoteView compose(Inputs in, Instant now, MarketCalendar calendar, Venue venue) {
    SessionState state = calendar.stateAt(now);
    LocalDate t = calendar.currentPnlDay(now);
    Optional<TradingDay> today = calendar.day(t);
    SessionType session = state.type();
    LocalDate mainDay = session == SessionType.PRE ? calendar.previousTradingDay(t) : t;
    LocalDate prevDay = calendar.previousTradingDay(mainDay);
    Optional<TradingDay> main = calendar.day(mainDay);

    BigDecimal prevClose = in.closes().apply(prevDay).orElse(null);
    Trade lastTrade = in.lastTrade();
    BigDecimal last;
    Long lastSize = null;
    Instant lastAt;
    ExtendedLine extended = null;
    if (session == SessionType.REGULAR) {
      last = lastTrade != null ? lastTrade.price() : null;
      lastSize = lastTrade != null ? lastTrade.size() : null;
      lastAt = lastTrade != null ? lastTrade.ts() : null;
    } else {
      BigDecimal mainClose = in.closes().apply(mainDay).orElse(null);
      Instant rthClose = main.map(TradingDay::rthCloseAt).orElse(null);
      if (mainClose == null && lastTrade != null && rthClose != null && !lastTrade.ts().isAfter(rthClose)) {
        mainClose = lastTrade.price();                     // the close is not known yet: the last regular print
      }
      last = mainClose;
      lastAt = rthClose;
      if (lastTrade != null && mainClose != null) {
        boolean pre = session == SessionType.PRE && today.isPresent()
            && !lastTrade.ts().isBefore(today.get().preOpenAt());
        boolean post = session != SessionType.PRE && rthClose != null && lastTrade.ts().isAfter(rthClose);
        if (pre || post) {
          BigDecimal change = lastTrade.price().subtract(mainClose);
          extended = new ExtendedLine(pre ? SessionType.PRE : SessionType.POST, lastTrade.price(), change,
              pct(change, mainClose), lastTrade.ts());
        }
      }
      if (last == null && lastTrade != null) {
        last = lastTrade.price();
        lastAt = lastTrade.ts();
      }
    }
    BigDecimal change = last != null && prevClose != null ? last.subtract(prevClose) : null;
    BigDecimal changePct = change != null ? pct(change, prevClose) : null;
    Optional<DayStats> stats = in.dayStats().apply(mainDay);

    Quote q = in.quote();
    Instant asOf = latest(q == null ? null : q.observedAt(), lastTrade == null ? null : lastTrade.observedAt());
    DataQuality quality = quality(in, now, session, state, venue);
    if (quality == DataQuality.NONE && last == null && prevClose == null && q == null) {
      return QuoteView.none(in.symbol(), in.source());
    }
    return new QuoteView(in.symbol(), q == null ? null : q.bid(), q == null ? null : q.bidSize(),
        q == null ? null : q.ask(), q == null ? null : q.askSize(), last, lastSize, lastAt, prevClose, change,
        changePct, stats.map(DayStats::open).orElse(null), stats.map(DayStats::high).orElse(null),
        stats.map(DayStats::low).orElse(null), stats.map(DayStats::volume).orElse(null), in.volumePartial(), extended,
        asOf, in.confirmedAt(), quality, in.source(), in.halted());
  }

  private DataQuality quality(Inputs in, Instant now, SessionType session, SessionState state, Venue venue) {
    Duration threshold = session == SessionType.REGULAR ? regularThreshold : extThreshold;
    boolean confirmed = in.confirmedAt() != null && Duration.between(in.confirmedAt(), now).compareTo(threshold) <= 0;
    boolean inVenueHours = session != SessionType.CLOSED && (venue == null || venue.open() == null
        || withinVenue(now, venue));
    if (in.simulated()) {
      if (in.quote() == null && in.lastTrade() == null) {
        return DataQuality.NONE;
      }
      return inVenueHours && !confirmed ? DataQuality.STALE : DataQuality.SIMULATED;
    }
    Quote q = in.quote();
    if (q != null && confirmed) {
      return in.consolidatedQuote() ? DataQuality.REALTIME_CONSOLIDATED : DataQuality.REALTIME_SINGLE_VENUE;
    }
    Trade t = in.lastTrade();
    if (t != null && t.consolidated() && t.observedAt() != null
        && Duration.between(t.observedAt(), now).compareTo(CONSOLIDATED_FRESH) <= 0) {
      return DataQuality.SYNTHETIC;
    }
    if (q == null && t == null) {
      return DataQuality.NONE;
    }
    if (inVenueHours) {
      return DataQuality.STALE;
    }
    return in.consolidatedQuote() ? DataQuality.REALTIME_CONSOLIDATED : DataQuality.REALTIME_SINGLE_VENUE;
  }

  private static boolean withinVenue(Instant now, Venue v) {
    LocalTime et = com.stocks.common.time.Et.time(now);
    return !et.isBefore(v.open()) && et.isBefore(v.close());
  }

  static BigDecimal pct(BigDecimal change, BigDecimal base) {
    if (change == null || base == null || base.signum() == 0) {
      return null;
    }
    return change.divide(base, 10, RoundingMode.HALF_EVEN);
  }

  private static Instant latest(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isAfter(b) ? a : b;
  }
}
