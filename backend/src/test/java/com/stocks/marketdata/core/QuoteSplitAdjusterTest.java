package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.calendar.InMemoryCalendars;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.SplitAdjustments;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * §14 "Reference prices after a split" for quotes: on and after the ex-date of an applied 2-for-1 split, a quote never
 * shows the split as a 50% drop (integration review: the SIM split smoke showed SIMS pre-market at "-50.03%").
 */
class QuoteSplitAdjusterTest {

  static final MarketCalendar CAL = InMemoryCalendars.bundled();
  static final LocalDate EX = LocalDate.of(2026, 10, 14);                 // Wednesday
  static final Map<LocalDate, BigDecimal> RAW_CLOSES = Map.of(LocalDate.of(2026, 10, 12), new BigDecimal("108.00"),
      LocalDate.of(2026, 10, 13), new BigDecimal("110.00"));
  final QuoteComposer composer = new QuoteComposer(Duration.ofSeconds(15), Duration.ofSeconds(120));

  /** One applied split: SIMS 1 → 2 on {@link #EX}. */
  static final SplitAdjustments SPLITS = new SplitAdjustments() {
    @Override
    public void record(String symbol, LocalDate exDate, BigDecimal oldRate, BigDecimal newRate, long id) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BigDecimal adjust(String symbol, BigDecimal price, LocalDate priceDate, LocalDate asOfDate) {
      if (price == null || !"SIMS".equals(symbol) || !priceDate.isBefore(EX) || asOfDate.isBefore(EX)) {
        return price;
      }
      return Rounding.SPLIT_ADJUST.divide(price, new BigDecimal(2));
    }
  };

  QuoteView compose(String symbol, Instant now, Trade last) {
    Quote q = new Quote(symbol, new BigDecimal("55.05"), 100, new BigDecimal("55.15"), 100, "V", "V", now, now,
        DataQuality.SIMULATED, "sim");
    return composer.compose(new QuoteComposer.Inputs(symbol, q, now, last, d -> Optional.ofNullable(RAW_CLOSES.get(d)),
        d -> Optional.empty(), false, false, true, true, "sim"), now, CAL, null);
  }

  static Trade trade(String symbol, String price, Instant at) {
    return new Trade(symbol, new BigDecimal(price), 100, at, at, "V", List.of(), true, true, "1", "sim");
  }

  static QuoteSplitAdjuster adjuster(Instant now) {
    return new QuoteSplitAdjuster(SPLITS, CAL, new MutableTimeSource(now));
  }

  @Test
  void preMarketOnTheExDateAdjustsYesterdaysCloseAndTheExtendedChange() {
    Instant now = Instant.parse("2026-10-14T13:00:00Z");                    // 09:00 ET, PRE
    QuoteView raw = compose("SIMS", now, trade("SIMS", "55.10", Instant.parse("2026-10-14T12:30:00Z")));
    assertThat(raw.extended().session()).isEqualTo(SessionType.PRE);
    assertThat(raw.extended().changePct()).isLessThan(new BigDecimal("-0.49"));       // the bug: "-50%"

    QuoteView v = adjuster(now).adjust(raw);
    assertThat(v.last()).isEqualByComparingTo("55.00");                     // Tuesday's 110.00 close, adjusted
    assertThat(v.prevClose()).isEqualByComparingTo("54.00");                // Monday's 108.00, adjusted
    assertThat(v.change()).isEqualByComparingTo("1.00");
    assertThat(v.changePct()).isEqualByComparingTo(QuoteComposer.pct(new BigDecimal("1.00"), new BigDecimal("54.00")));
    assertThat(v.extended().price()).isEqualByComparingTo("55.10");         // today's print: never adjusted
    assertThat(v.extended().change()).isEqualByComparingTo("0.10");
    assertThat(v.extended().changePct()).isEqualByComparingTo(
        QuoteComposer.pct(new BigDecimal("0.10"), new BigDecimal("55.00")));
    assertThat(v.bid()).isEqualByComparingTo(raw.bid());
    assertThat(v.ask()).isEqualByComparingTo(raw.ask());
  }

  @Test
  void regularSessionOnTheExDateAdjustsOnlyThePreviousClose() {
    Instant now = Instant.parse("2026-10-14T15:00:00Z");                    // 11:00 ET
    QuoteView v = adjuster(now).adjust(compose("SIMS", now, trade("SIMS", "55.50", now)));
    assertThat(v.last()).isEqualByComparingTo("55.50");
    assertThat(v.prevClose()).isEqualByComparingTo("55.00");
    assertThat(v.change()).isEqualByComparingTo("0.50");
    assertThat(v.extended()).isNull();
  }

  /**
   * Review regression: between the 03:45 application and the 04:00 pre-open the P&amp;L day is still Tuesday, but the
   * positions are split, so the quote (and the ticket's check 19 reference, its {@code last}) is split-adjusted too.
   */
  @Test
  void betweenTheApplicationAndThePreOpenTheQuoteIsAlreadyAdjusted() {
    Instant now = Instant.parse("2026-10-14T07:50:00Z");                    // Wed 03:50 ET, CLOSED
    QuoteView raw = compose("SIMS", now, trade("SIMS", "110.00", Instant.parse("2026-10-13T19:59:00Z")));
    QuoteView v = adjuster(now).adjust(raw);
    assertThat(v.last()).isEqualByComparingTo("55.00");
    assertThat(v.prevClose()).isEqualByComparingTo("54.00");
    assertThat(v.change()).isEqualByComparingTo("1.00");
  }

  @Test
  void beforeTheExDateOrWithoutASplitTheViewIsUnchanged() {
    Instant before = Instant.parse("2026-10-13T15:00:00Z");
    QuoteView raw = compose("SIMS", before, trade("SIMS", "110.40", before));
    assertThat(adjuster(before).adjust(raw)).isSameAs(raw);
    Instant on = Instant.parse("2026-10-14T15:00:00Z");
    QuoteView other = compose("AAPL", on, trade("AAPL", "230.00", on));
    assertThat(adjuster(on).adjust(other)).isSameAs(other);
    assertThat(adjuster(on).adjust(null)).isNull();
  }
}
