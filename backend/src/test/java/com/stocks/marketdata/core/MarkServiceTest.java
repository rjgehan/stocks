package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocks.calendar.InMemoryCalendars;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.marketdata.MarkBasis;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.testkit.TestDb;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §15.5 MarkService preference order, split adjustment of fallbacks and the corporate-action hold. */
class MarkServiceTest {

  static final Instant NOW = Instant.parse("2026-10-09T15:00:00Z");               // Fri 11:00 ET, REGULAR
  final MutableTimeSource time = new MutableTimeSource(NOW);
  final MarketCalendar calendar = InMemoryCalendars.bundled();
  MarketFeed feed;
  DailyMarkRepository marks;
  SplitAdjustmentsImpl splits;
  Set<String> holds;
  MarkService service;
  TestDb db;

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    feed = mock(MarketFeed.class);
    marks = new DailyMarkRepository(db.dataSource());
    splits = new SplitAdjustmentsImpl(db.dataSource(), time);
    holds = new java.util.HashSet<>();
    service = new MarkService(feed, calendar, time, marks, splits, holds::contains, Duration.ofSeconds(15),
        Duration.ofSeconds(120));
  }

  @AfterEach
  void closeDb() throws Exception {
    ((AutoCloseable) db.dataSource()).close();         // PG has a connection limit: release the pool
  }

  static Trade trade(String price, Instant at, boolean consolidated) {
    return new Trade("AAPL", new BigDecimal(price), 100, at, at, null, List.of(), consolidated, true, "1", "x");
  }

  static Quote quote(String bid, String ask, Instant at) {
    return new Quote("AAPL", new BigDecimal(bid), 100, new BigDecimal(ask), 100, "V", "V", at, at,
        DataQuality.REALTIME_SINGLE_VENUE, "alpaca");
  }

  void inputs(Quote q, Instant confirmed, Trade consolidated, Trade last, Map<LocalDate, BigDecimal> closes) {
    when(feed.markInputs("AAPL")).thenReturn(new MarketFeed.MarkInputs(q, confirmed, consolidated, last, closes,
        false));
  }

  @Test
  void preferenceOrderDuringTheSession() {
    Trade sip = trade("227.50", NOW.minusSeconds(30), true);
    inputs(quote("227.40", "227.44", NOW), NOW, sip, sip, Map.of());
    Mark m = service.mark("AAPL");
    assertThat(m.basis()).isEqualTo(MarkBasis.CONSOLIDATED_LAST);
    assertThat(m.price()).isEqualByComparingTo("227.50");
    assertThat(m.quality()).isEqualTo(DataQuality.REALTIME_CONSOLIDATED);

    inputs(quote("227.40", "227.44", NOW), NOW.minusSeconds(5), trade("227.50", NOW.minusSeconds(90), true), null,
        Map.of());
    m = service.mark("AAPL");
    assertThat(m.basis()).isEqualTo(MarkBasis.IEX_MID);
    assertThat(m.price()).isEqualByComparingTo("227.42");

    inputs(quote("227.40", "227.44", NOW.minusSeconds(60)), NOW.minusSeconds(60), null,
        trade("227.30", NOW.minusSeconds(600), false), Map.of());
    m = service.mark("AAPL");
    assertThat(m.basis()).isEqualTo(MarkBasis.IEX_LAST);
    assertThat(m.quality()).isEqualTo(DataQuality.REALTIME_SINGLE_VENUE);
    time.advance(Duration.ofMinutes(20));
    assertThat(service.mark("AAPL").quality()).isEqualTo(DataQuality.STALE);
  }

  @Test
  void prevCloseFallbackIsSplitAdjusted() {
    LocalDate prev = LocalDate.of(2026, 10, 8);
    inputs(null, null, null, trade("230.00", Instant.parse("2026-10-08T19:59:00Z"), false),
        Map.of(prev, new BigDecimal("230.00")));
    Mark m = service.mark("AAPL");
    assertThat(m.basis()).isEqualTo(MarkBasis.PREV_CLOSE);
    assertThat(m.price()).isEqualByComparingTo("230.00");
    splits.record("AAPL", LocalDate.of(2026, 10, 9), BigDecimal.ONE, BigDecimal.TWO, 1);
    assertThat(service.mark("AAPL").price()).isEqualByComparingTo("115.00");
  }

  @Test
  void closedUsesTheDayEndMarkThenTheLastTrade() {
    time.set(Instant.parse("2026-10-10T15:00:00Z"));                            // Saturday
    inputs(null, null, null, trade("231.00", Instant.parse("2026-10-09T23:59:00Z"), false), Map.of());
    Mark lastTrade = service.mark("AAPL");
    assertThat(lastTrade.basis()).isEqualTo(MarkBasis.IEX_LAST);
    assertThat(lastTrade.price()).isEqualByComparingTo("231.00");
    marks.upsertDayEnd("AAPL", LocalDate.of(2026, 10, 9), new BigDecimal("230.75"), "SIP_1MIN",
        DailyMarkRepository.FINAL, time.now());
    Mark dayEnd = service.mark("AAPL");
    assertThat(dayEnd.basis()).isEqualTo(MarkBasis.DAY_END);
    assertThat(dayEnd.price()).isEqualByComparingTo("230.75");
    assertThat(dayEnd.asOf()).isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
  }

  @Test
  void heldSymbolsAreMarkedAtTheLastDayEndUnadjusted() {
    marks.upsertDayEnd("AAPL", LocalDate.of(2026, 10, 8), new BigDecimal("240.00"), "SIP_1MIN",
        DailyMarkRepository.FINAL, time.now());
    splits.record("AAPL", LocalDate.of(2026, 10, 9), BigDecimal.ONE, BigDecimal.TWO, 1);
    holds.add("AAPL");
    Mark m = service.mark("AAPL");
    assertThat(m.price()).isEqualByComparingTo("240.00");
    assertThat(m.quality()).isEqualTo(DataQuality.STALE);
  }

  @Test
  void simMarksAreSimulated() {
    when(feed.markInputs("SIMA")).thenReturn(new MarketFeed.MarkInputs(null, NOW, null,
        trade("10.00", NOW, true), Map.of(), true));
    Mark m = service.mark("SIMA");
    assertThat(m.quality()).isEqualTo(DataQuality.SIMULATED);
    assertThat(m.basis()).isEqualTo(MarkBasis.SIMULATED);
  }

  /**
   * Integration smoke regression: after the 03:45 ET split of SIMS (2-for-1) and before its first post-split print,
   * the SIM's last print is Tuesday's raw price; marking 2 shares at it showed +100% on the live board.
   */
  @Test
  void simMarksFromBeforeAnAppliedSplitAreAdjusted() {
    LocalDate ex = LocalDate.of(2026, 10, 14);
    time.set(Instant.parse("2026-10-14T07:50:00Z"));                              // Wed 03:50 ET, CLOSED
    splits.record("SIMS", ex, BigDecimal.ONE, BigDecimal.TWO, 1);
    Instant tuesdayClose = Instant.parse("2026-10-13T20:00:00Z");
    when(feed.markInputs("SIMS")).thenReturn(new MarketFeed.MarkInputs(null, tuesdayClose, null,
        trade("108.47", tuesdayClose, true), Map.of(), true));
    assertThat(service.mark("SIMS").price()).isEqualByComparingTo("54.235");
    Instant wednesday = Instant.parse("2026-10-14T13:31:00Z");                     // the first post-split print
    time.set(wednesday);
    when(feed.markInputs("SIMS")).thenReturn(new MarketFeed.MarkInputs(null, wednesday, null,
        trade("54.40", wednesday, true), Map.of(), true));
    assertThat(service.mark("SIMS").price()).isEqualByComparingTo("54.40");
  }
}
