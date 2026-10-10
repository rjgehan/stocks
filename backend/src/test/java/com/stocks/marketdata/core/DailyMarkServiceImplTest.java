package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocks.calendar.InMemoryCalendars;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.marketdata.sim.SimScenarios;
import com.stocks.marketdata.sim.SimulatedMarketDataProvider;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.marketdata.spi.Capabilities;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.testkit.TestDb;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** §15.5 daily marks: official closes and day-end marks with their bases, qualities and fallbacks. */
class DailyMarkServiceImplTest {

  static final LocalDate D = LocalDate.of(2026, 10, 9);
  final MarketCalendar calendar = InMemoryCalendars.bundled();

  @Test
  void simWritesFinalSimulatedMarksOnlyAfterTheSessions() {
    TestDb db = TestDb.fresh();
    MutableTimeSource time = new MutableTimeSource(Instant.parse("2026-10-09T19:00:00Z"));   // 15:00 ET
    SimulatedMarketDataProvider sim = new SimulatedMarketDataProvider(42, calendar, Universe.load(),
        SimScenarios.none(), time);
    DailyMarkRepository repo = new DailyMarkRepository(db.dataSource());
    DailyMarkServiceImpl marks = new DailyMarkServiceImpl(sim, sim, mock(MarketFeed.class), repo, calendar, time);
    marks.ensureOfficialCloses(D, Set.of("AAPL"));
    assertThat(repo.officialClose("AAPL", D)).isEmpty();                         // the session is not over
    time.set(Instant.parse("2026-10-09T20:20:00Z"));
    marks.ensureOfficialCloses(D, Set.of("AAPL", "SPY", "NOPE"));
    assertThat(repo.officialClose("AAPL", D)).contains(new BigDecimal("255.0000"));
    assertThat(repo.officialCloses(D, Set.of("AAPL", "SPY", "NOPE"))).containsOnlyKeys("AAPL", "SPY");
    marks.ensureDayEndMarks(D, Set.of("AAPL"));
    assertThat(repo.dayEndMark("AAPL", D)).isEmpty();                            // POST still running
    time.set(Instant.parse("2026-10-10T00:20:00Z"));
    marks.ensureDayEndMarks(D, Set.of("AAPL"));
    assertThat(repo.withDayEnd(D, DailyMarkRepository.FINAL)).containsExactly("AAPL");
    BigDecimal mark = repo.dayEndMark("AAPL", D).orElseThrow();
    assertThat(mark).isEqualByComparingTo(DailyMarkServiceImpl.simDayEnd(sim, calendar, "AAPL", D, time.now())
        .orElseThrow());
    marks.ensureDayEndMarks(D, Set.of("AAPL"));                                  // idempotent
    assertThat(db.jdbc().sql("SELECT day_end_basis FROM daily_mark WHERE symbol = 'AAPL'").query(String.class)
        .single()).isEqualTo("SIMULATED");
  }

  @Test
  void liveUsesTheLastSip1MinBarThenFallsBack() {
    TestDb db = TestDb.fresh();
    MutableTimeSource time = new MutableTimeSource(Instant.parse("2026-10-10T00:20:00Z"));   // 20:20 ET
    List<BarQuery> queries = new ArrayList<>();
    MarketDataProvider sip = new MarketDataProvider() {
      @Override
      public String id() {
        return "alpaca";
      }

      @Override
      public Capabilities capabilities() {
        return null;
      }

      @Override
      public Map<String, List<Bar>> bars(BarQuery q, RefreshPriority priority) {
        queries.add(q);
        if (q.tf() == Timeframe.DAY1) {
          return Map.of("AAPL", List.of(bar("AAPL", Timeframe.DAY1, "2026-10-09T04:00:00Z", "227.41")),
              "MSFT", List.of(bar("MSFT", Timeframe.DAY1, "2026-10-09T04:00:00Z", "512.10")));
        }
        return Map.of("AAPL", List.of(bar("AAPL", Timeframe.MIN1, "2026-10-09T23:58:00Z", "228.00"),
            bar("AAPL", Timeframe.MIN1, "2026-10-09T23:59:00Z", "228.05")));
      }
    };
    MarketFeed feed = mock(MarketFeed.class);
    when(feed.markInputs(anyString())).thenReturn(new MarketFeed.MarkInputs(null, null, null, null, Map.of(), false));
    when(feed.markInputs("TSLA")).thenReturn(new MarketFeed.MarkInputs(null, null, null,
        new Trade("TSLA", new BigDecimal("250.00"), 1, Instant.parse("2026-10-09T23:00:00Z"),
            Instant.parse("2026-10-09T23:00:00Z"), "V", List.of(), false, true, "1", "alpaca"), Map.of(), false));
    DailyMarkRepository repo = new DailyMarkRepository(db.dataSource());
    DailyMarkServiceImpl marks = new DailyMarkServiceImpl(sip, null, feed, repo, calendar, time);
    marks.ensureOfficialCloses(D, Set.of("AAPL", "MSFT"));
    assertThat(queries.getFirst().feed()).isEqualTo("sip");
    assertThat(queries.getFirst().adj().name()).isEqualTo("RAW");
    marks.ensureDayEndMarks(D, Set.of("AAPL", "MSFT", "TSLA", "NONE"));
    BarQuery minute = queries.getLast();
    assertThat(minute.tf()).isEqualTo(Timeframe.MIN1);
    assertThat(minute.to()).isEqualTo(Instant.parse("2026-10-09T23:59:59Z"));
    assertThat(repo.dayEndMark("AAPL", D)).contains(new BigDecimal("228.0500"));
    assertThat(repo.withDayEnd(D, DailyMarkRepository.FINAL)).containsExactly("AAPL");
    assertThat(repo.dayEndMark("MSFT", D)).contains(new BigDecimal("512.1000"));       // OFFICIAL_CLOSE
    assertThat(repo.dayEndMark("TSLA", D)).contains(new BigDecimal("250.0000"));       // LAST_TRADE
    assertThat(repo.provisional(D)).containsExactly("MSFT", "TSLA");
    assertThat(repo.dayEndMark("NONE", D)).isEmpty();
  }

  static Bar bar(String symbol, Timeframe tf, String start, String close) {
    BigDecimal c = new BigDecimal(close);
    return new Bar(symbol, tf, Instant.parse(start), c, c, c, c, 100, c, 1, false);
  }
}
