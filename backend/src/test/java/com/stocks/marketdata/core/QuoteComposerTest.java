package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.calendar.InMemoryCalendars;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** §15.5 QuoteView composition, Webull style: the main line is the regular session, an extended line PRE/POST. */
class QuoteComposerTest {

  static final MarketCalendar CAL = InMemoryCalendars.bundled();
  static final Map<LocalDate, BigDecimal> CLOSES = Map.of(LocalDate.of(2026, 10, 8), new BigDecimal("225.00"),
      LocalDate.of(2026, 10, 9), new BigDecimal("230.00"));
  final QuoteComposer composer = new QuoteComposer(Duration.ofSeconds(15), Duration.ofSeconds(120));
  static final QuoteComposer.Venue IEX = new QuoteComposer.Venue(LocalTime.of(8, 0), LocalTime.of(17, 0));

  static QuoteComposer.Inputs inputs(Instant confirmedAt, Trade last, boolean consolidatedTrade) {
    Quote q = new Quote("AAPL", new BigDecimal("229.90"), 100, new BigDecimal("230.10"), 100, "V", "V",
        confirmedAt, confirmedAt, DataQuality.REALTIME_SINGLE_VENUE, "alpaca");
    return new QuoteComposer.Inputs("AAPL", q, confirmedAt, last, d -> Optional.ofNullable(CLOSES.get(d)),
        d -> Optional.empty(), true, false, false, false, "alpaca");
  }

  static Trade trade(String price, Instant at, boolean consolidated) {
    return new Trade("AAPL", new BigDecimal(price), 100, at, at, "V", List.of(), consolidated, true, "1", "x");
  }

  @Test
  void regularSessionMainLineIsTheLastPrint() {
    Instant now = Instant.parse("2026-10-09T15:00:00Z");
    QuoteView v = composer.compose(inputs(now, trade("229.95", now, false), false), now, CAL, IEX);
    assertThat(v.last()).isEqualByComparingTo("229.95");
    assertThat(v.prevClose()).isEqualByComparingTo("225.00");
    assertThat(v.change()).isEqualByComparingTo("4.95");
    assertThat(v.changePct()).isEqualByComparingTo("0.022");
    assertThat(v.extended()).isNull();
    assertThat(v.quality()).isEqualTo(DataQuality.REALTIME_SINGLE_VENUE);
  }

  /**
   * Review regression: Alpaca sends bp/ap = 0 when IEX has no quote today (weekends, before 08:00 ET); the UI showed
   * "Bid 0.00 × 0" and the ticket pre-filled a 0.00 limit. A non-positive side is now "no price".
   */
  @Test
  void aZeroBidOrAskIsNoPrice() {
    Instant now = Instant.parse("2026-10-10T15:00:00Z");                         // Saturday
    Quote zero = new Quote("AAPL", BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, null, null, now, now,
        DataQuality.REALTIME_SINGLE_VENUE, "alpaca");
    QuoteView v = composer.compose(new QuoteComposer.Inputs("AAPL", zero, now, trade("255.01", now, false),
        d -> Optional.ofNullable(CLOSES.get(d)), d -> Optional.empty(), true, false, false, false, "alpaca"), now, CAL,
        IEX);
    assertThat(v.bid()).isNull();
    assertThat(v.bidSize()).isNull();
    assertThat(v.ask()).isNull();
    assertThat(v.askSize()).isNull();
    Quote oneSided = new Quote("AAPL", BigDecimal.ZERO, 0, new BigDecimal("230.10"), 100, null, "V", now, now,
        DataQuality.REALTIME_SINGLE_VENUE, "alpaca");
    QuoteView w = composer.compose(new QuoteComposer.Inputs("AAPL", oneSided, now, null,
        d -> Optional.ofNullable(CLOSES.get(d)), d -> Optional.empty(), true, false, false, false, "alpaca"), now, CAL,
        IEX);
    assertThat(w.bid()).isNull();
    assertThat(w.ask()).isEqualByComparingTo("230.10");
    assertThat(w.askSize()).isEqualTo(100L);
  }

  @Test
  void afterHoursShowsTheCloseAndAnExtendedLine() {
    Instant now = Instant.parse("2026-10-09T21:00:00Z");                         // 17:00 ET, POST
    QuoteView v = composer.compose(inputs(now.minusSeconds(5), trade("231.15", now.minusSeconds(5), false), false),
        now, CAL, IEX);
    assertThat(v.last()).isEqualByComparingTo("230.00");
    assertThat(v.extended().session()).isEqualTo(SessionType.POST);
    assertThat(v.extended().price()).isEqualByComparingTo("231.15");
    assertThat(v.extended().change()).isEqualByComparingTo("1.15");
    assertThat(v.extended().changePct()).isEqualByComparingTo("0.005");
  }

  @Test
  void preMarketComparesWithThePriorClose() {
    Instant now = Instant.parse("2026-10-12T12:30:00Z");                         // Mon 08:30 ET, PRE
    QuoteView v = composer.compose(inputs(now, trade("228.00", now.minusSeconds(2), false), false), now, CAL, IEX);
    assertThat(v.last()).isEqualByComparingTo("230.00");                         // Friday's close
    assertThat(v.prevClose()).isEqualByComparingTo("225.00");                    // Thursday's close
    assertThat(v.extended().session()).isEqualTo(SessionType.PRE);
    assertThat(v.extended().change()).isEqualByComparingTo("-2.00");
  }

  @Test
  void staleOnlyInsideVenueHoursAndSyntheticFromAFreshConsolidatedPrint() {
    Instant now = Instant.parse("2026-10-09T15:00:00Z");
    QuoteView stale = composer.compose(inputs(now.minusSeconds(60), trade("229.95", now.minusSeconds(60), false),
        false), now, CAL, IEX);
    assertThat(stale.quality()).isEqualTo(DataQuality.STALE);
    QuoteView synthetic = composer.compose(inputs(now.minusSeconds(60), trade("229.95", now.minusSeconds(3), true),
        false), now, CAL, IEX);
    assertThat(synthetic.quality()).isEqualTo(DataQuality.SYNTHETIC);
    Instant dark = Instant.parse("2026-10-09T22:30:00Z");                        // 18:30 ET: IEX is dark
    QuoteView quiet = composer.compose(inputs(dark.minusSeconds(3600), trade("230.00", dark.minusSeconds(3600), false),
        false), dark, CAL, IEX);
    assertThat(quiet.quality()).isEqualTo(DataQuality.REALTIME_SINGLE_VENUE);
  }
}
