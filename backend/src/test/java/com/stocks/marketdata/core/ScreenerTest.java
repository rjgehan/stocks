package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stocks.calendar.InMemoryCalendars;
import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.error.ApiException;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.ScreenCursor;
import com.stocks.contract.marketdata.ScreenPreset;
import com.stocks.contract.marketdata.ScreenQuery;
import com.stocks.contract.marketdata.ScreenResult;
import com.stocks.contract.marketdata.ScreenSort;
import com.stocks.contract.marketdata.Sector;
import com.stocks.contract.marketdata.SortOrder;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.marketdata.spi.Capabilities;
import com.stocks.marketdata.spi.CompanyProfile;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §15.10 screener: presets, filters, sorting and paging; the universe table (market cap from shares × price, relative
 * volume on the quote's own basis, sectors, rows without a price left out, a throttled warm pass); the statistics task
 * (20-day averages once per trading day, a few profiles per tick, retries) and its table on H2 and, under
 * {@code -Ppg-it}, PostgreSQL (every MERGE with null parameters).
 */
class ScreenerTest {

  static final MarketCalendar CAL = InMemoryCalendars.bundled();
  static final Capabilities CAPS = new Capabilities(false, false, false, 0, 60, Duration.ZERO, null, null, true, false,
      true, false, false, true, false, false);

  TestDb db;
  MutableTimeSource time;
  AssetService assets;
  ScreenerStatRepository repo;

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();                              // Fri 2026-10-09 10:00 ET
    Universe universe = Universe.load();
    assets = new AssetService(new AssetRepository(db.dataSource()), universe, time);
    assets.reload();
    assets.ensure(universe.entries().stream().map(Universe.Entry::asset).toList(), "manual");
    repo = new ScreenerStatRepository(db.dataSource());
  }

  @AfterEach
  void closeDb() throws Exception {
    ((AutoCloseable) db.dataSource()).close();
  }

  // ------------------------------------------------------------------------------------------- fixtures

  static QuoteView quote(String symbol, String last, String changePct, Long volume, boolean partial,
                         DataQuality quality) {
    BigDecimal l = last == null ? null : new BigDecimal(last);
    BigDecimal pct = changePct == null ? null : new BigDecimal(changePct);
    BigDecimal change = l == null || pct == null ? null : l.multiply(pct).setScale(2, java.math.RoundingMode.HALF_EVEN);
    return new QuoteView(symbol, null, null, null, null, l, null, null, null, change, pct, null, null, null, volume,
        partial, null, TestTimes.START, TestTimes.START, quality, "test", false);
  }

  /** A feed that knows only the given quotes (everything else has none) and counts warm passes. */
  static final class Feed {
    final MarketFeed feed = mock(MarketFeed.class);
    final Map<String, QuoteView> quotes = new HashMap<>();

    Feed put(QuoteView q) {
      quotes.put(q.symbol(), q);
      return this;
    }

    Feed() {
      when(feed.peek(any())).thenAnswer(inv -> {
        Map<String, QuoteView> out = new LinkedHashMap<>();
        for (String s : (Collection<String>) inv.getArgument(0)) {
          out.put(s, quotes.getOrDefault(s, QuoteView.none(s, "test")));
        }
        return out;
      });
    }
  }

  ScreenerStats stats(MarketDataProvider bars, MarketDataProvider profiles, boolean iexBasis, boolean local,
                      AtomicLong nanos, int perTick, Collection<String> symbols) {
    Function<String, AssetType> types = s -> assets.asset(s).map(a -> a.type()).orElse(null);
    return new ScreenerStats(repo, new ScreenerStats.Sources(bars, local ? null : "sip", iexBasis, profiles, local),
        () -> symbols, types, CAL, time, perTick, nanos::get);
  }

  ScreenerService.SplitFactor splitFactor = ScreenerService.SplitFactor.NONE;

  ScreenerService service(Feed feed, ScreenerStats stats, boolean simulated, AtomicLong nanos) {
    return new ScreenerService(feed.feed, assets, Sectors.load(), stats, q -> q, splitFactor, time, simulated,
        nanos::get);
  }

  static ScreenQuery query(ScreenPreset preset) {
    return ScreenQuery.of(preset, 25);
  }

  static List<String> symbols(ScreenResult r) {
    return r.rows().stream().map(ScreenResult.Row::symbol).toList();
  }

  // ------------------------------------------------------------------------------------------- the screener

  @Test
  void rowsCarryMarketCapRelativeVolumeAndSectorAndUnpricedSymbolsAreLeftOut() {
    AtomicLong nanos = new AtomicLong(1);
    repo.upsertProfile("AAPL", new ScreenerStatRepository.Profile(new BigDecimal("15000000000"),
        new BigDecimal("3000000000000"), "Technology", "finnhub"), time.now());
    repo.upsertProfile("F", new ScreenerStatRepository.Profile(null, new BigDecimal("47000000000"), "Automobiles",
        "finnhub"), time.now());
    repo.upsertVolumes(Map.of(
        "AAPL", new ScreenerStatRepository.Volumes(50_000_000L, 1_250_000L, 20, LocalDate.of(2026, 10, 8)),
        "F", new ScreenerStatRepository.Volumes(60_000_000L, null, 20, LocalDate.of(2026, 10, 8)),
        "SPY", new ScreenerStatRepository.Volumes(70_000_000L, 2_000_000L, 20, LocalDate.of(2026, 10, 8))),
        time.now());
    Feed feed = new Feed()
        .put(quote("AAPL", "227.40", "0.0120", 2_500_000L, true, DataQuality.REALTIME_SINGLE_VENUE))
        .put(quote("F", "11.84", "-0.0200", 30_000_000L, false, DataQuality.REALTIME_SINGLE_VENUE))
        .put(quote("SPY", "668.21", "0.0010", 1_000_000L, true, DataQuality.REALTIME_SINGLE_VENUE))
        .put(quote("NVDA", null, null, null, false, DataQuality.STALE));          // no price: left out
    ScreenerStats st = stats(null, null, true, false, nanos, 0, List.of());
    ScreenResult r = service(feed, st, false, nanos).screen(new ScreenQuery(null, null, null, null, null, null, null,
        null, null, null, Set.of(), Set.of(), ScreenSort.SYMBOL, null, 25, 0));

    assertThat(symbols(r)).containsExactly("AAPL", "F", "SPY");
    assertThat(r.order()).isEqualTo(SortOrder.ASC);
    ScreenResult.Row aapl = r.rows().get(0);
    assertThat(aapl.marketCap()).isEqualByComparingTo("3411000000000");       // 15B shares × 227.40, live
    assertThat(aapl.sector()).isEqualTo(Sector.INFORMATION_TECHNOLOGY);       // sectors.csv
    assertThat(aapl.avgVolume()).isEqualTo(50_000_000L);                       // SIP basis for display
    assertThat(aapl.relVolume()).isEqualByComparingTo("2.00");                 // IEX 2.5M ÷ IEX 1.25M
    assertThat(aapl.volumeIsPartial()).isTrue();
    ScreenResult.Row f = r.rows().get(1);
    assertThat(f.marketCap()).isEqualByComparingTo("47000000000");             // no shares: the stored cap
    assertThat(f.relVolume()).isEqualByComparingTo("0.50");                    // full volume ÷ SIP average
    ScreenResult.Row spy = r.rows().get(2);
    assertThat(spy.marketCap()).isNull();                                      // ETFs have no cap
    assertThat(spy.sector()).isNull();
    assertThat(r.coverage()).isEqualTo(new ScreenResult.Coverage(assets.universe().size(), 3, 2, 3));
    assertThat(r.total()).isEqualTo(3);
    assertThat(r.nextOffset()).isNull();
  }

  /**
   * Review finding: an IEX-only (partial) volume was divided by the SIP average when no IEX average was stored
   * (about 0.05×, so never UNUSUAL_VOLUME). §15.10: the same basis or none.
   */
  @Test
  void aPartialVolumeWithoutAnIexAverageHasNoRelativeVolume() {
    AtomicLong nanos = new AtomicLong(1);
    repo.upsertVolumes(Map.of("AAPL", new ScreenerStatRepository.Volumes(50_000_000L, null, 20,
        LocalDate.of(2026, 10, 8))), time.now());
    Feed feed = new Feed().put(quote("AAPL", "227.40", "0.0120", 2_500_000L, true,
        DataQuality.REALTIME_SINGLE_VENUE));
    ScreenResult r = service(feed, stats(null, null, true, false, nanos, 0, List.of()), false, nanos)
        .screen(new ScreenQuery(null, null, null, null, null, null, null, null, null, null, Set.of(), Set.of(),
            ScreenSort.SYMBOL, null, 25, 0));
    ScreenResult.Row aapl = r.rows().getFirst();
    assertThat(aapl.avgVolume()).isEqualTo(50_000_000L);                      // still shown
    assertThat(aapl.relVolume()).as("IEX volume is never compared with the SIP average").isNull();
  }

  /**
   * Review finding: after a split the live price is post-split while the stored share count (refreshed every 30
   * days) and the volume averages (fetched before the ex-date) are not, so the market cap was off by the ratio.
   * They are now rescaled by the applied splits since they were fetched (price_adjustment).
   */
  @Test
  void aSplitAfterTheProfileAndTheAveragesWereFetchedRescalesThem() {
    AtomicLong nanos = new AtomicLong(1);
    repo.upsertProfile("AAPL", new ScreenerStatRepository.Profile(new BigDecimal("15000000000"),
        new BigDecimal("3411000000000"), "Technology", "finnhub"), time.now());
    repo.upsertVolumes(Map.of("AAPL", new ScreenerStatRepository.Volumes(50_000_000L, 1_000_000L, 20,
        LocalDate.of(2026, 10, 8))), time.now());
    SplitAdjustmentsImpl splits = new SplitAdjustmentsImpl(db.dataSource(), time);
    splitFactor = splits::shareFactor;
    MarketDataProvider profiles = mock(MarketDataProvider.class);
    ScreenerStats st = stats(null, profiles, true, false, nanos, 3, List.of("AAPL"));
    // a 4-for-1 split with ex-date Mon 2026-10-12; the profile is not due again for 30 days
    splits.record("AAPL", LocalDate.of(2026, 10, 12), new BigDecimal("1"), new BigDecimal("4"), 1);
    time.set(Instant.parse("2026-10-12T14:00:00Z"));
    st.refresh(3);
    verify(profiles, never()).profile(any(), any(RefreshPriority.class));
    Feed feed = new Feed().put(quote("AAPL", "56.85", "0.0000", 8_000_000L, true,
        DataQuality.REALTIME_SINGLE_VENUE));
    ScreenResult.Row aapl = service(feed, st, false, nanos).screen(new ScreenQuery(null, null, null, null, null,
        null, null, null, null, null, Set.of(), Set.of(), ScreenSort.SYMBOL, null, 25, 0)).rows().getFirst();
    assertThat(aapl.marketCap()).isEqualByComparingTo("3411000000000");     // 60B shares × 56.85
    assertThat(aapl.avgVolume()).isEqualTo(200_000_000L);
    assertThat(aapl.relVolume()).isEqualByComparingTo("2.00");               // 8M ÷ (1M × 4)

    // statistics fetched on or after the ex-date are already on the new basis
    repo.upsertProfile("AAPL", new ScreenerStatRepository.Profile(new BigDecimal("60000000000"), null, null,
        "finnhub"), time.now());
    ScreenerStats fresh = stats(null, null, true, false, nanos, 0, List.of());
    ScreenResult.Row again = service(feed, fresh, false, new AtomicLong(1)).screen(new ScreenQuery(null, null, null,
        null, null, null, null, null, null, null, Set.of(), Set.of(), ScreenSort.SYMBOL, null, 25, 0)).rows()
        .getFirst();
    assertThat(again.marketCap()).isEqualByComparingTo("3411000000000");
  }

  @Test
  void presetsFilterAndSortAndExplicitValuesReplaceThePresets() {
    AtomicLong nanos = new AtomicLong(1);
    Feed feed = new Feed()
        .put(quote("AAPL", "227.40", "0.0120", 50_000_000L, false, DataQuality.SIMULATED))
        .put(quote("MSFT", "512.00", "0.0300", 20_000_000L, false, DataQuality.SIMULATED))
        .put(quote("F", "11.84", "-0.0200", 90_000_000L, false, DataQuality.SIMULATED))
        .put(quote("KO", "68.00", "0", 10_000_000L, false, DataQuality.SIMULATED))
        .put(quote("SPY", "668.21", "0.0400", 70_000_000L, false, DataQuality.SIMULATED));
    ScreenerService s = service(feed, stats(null, null, false, false, nanos, 0, List.of()), true, nanos);

    assertThat(symbols(s.screen(query(ScreenPreset.TOP_GAINERS)))).containsExactly("MSFT", "AAPL");   // no ETF, no 0
    assertThat(symbols(s.screen(query(ScreenPreset.TOP_LOSERS)))).containsExactly("F");
    assertThat(symbols(s.screen(query(ScreenPreset.MOST_ACTIVE)))).containsExactly("F", "AAPL", "MSFT", "KO");
    assertThat(symbols(s.screen(query(ScreenPreset.ETFS)))).containsExactly("SPY");
    // an explicit type list replaces the preset's; an explicit sort keeps the preset's filters
    ScreenQuery etfGainers = new ScreenQuery(ScreenPreset.TOP_GAINERS, null, null, null, null, null, null, null, null,
        null, Set.of(), EnumSet.of(AssetType.ETF, AssetType.COMMON_STOCK), ScreenSort.PRICE, null, 25, 0);
    ScreenResult r = s.screen(etfGainers);
    assertThat(symbols(r)).containsExactly("SPY", "MSFT", "AAPL");
    assertThat(r.sort()).isEqualTo(ScreenSort.PRICE);
    assertThat(r.order()).isEqualTo(SortOrder.DESC);
    assertThat(r.preset()).isEqualTo(ScreenPreset.TOP_GAINERS);
    // price band and sector
    ScreenQuery band = new ScreenQuery(null, new BigDecimal("10"), new BigDecimal("300"), null, null, null, null,
        null, null, null, EnumSet.of(Sector.INFORMATION_TECHNOLOGY, Sector.CONSUMER_DISCRETIONARY), Set.of(),
        ScreenSort.CHANGE_PCT, SortOrder.ASC, 25, 0);
    assertThat(symbols(s.screen(band))).containsExactly("F", "AAPL");
    // market-cap screens need a cap: none stored, so nothing matches (never a guess)
    assertThat(s.screen(query(ScreenPreset.LARGE_CAPS_UNDER_50)).rows()).isEmpty();
  }

  @Test
  void nullSortKeysGoLastInBothOrdersAndTiesBreakBySymbol() {
    ScreenResult.Row a = row("A", "10", null);
    ScreenResult.Row b = row("B", "20", "5");
    ScreenResult.Row c = row("C", "20", "5");
    ScreenResult.Row d = row("D", "5", null);
    List<ScreenResult.Row> rows = new ArrayList<>(List.of(d, c, b, a));
    rows.sort(ScreenerService.comparator(ScreenSort.MARKET_CAP, SortOrder.DESC));
    assertThat(rows).extracting(ScreenResult.Row::symbol).containsExactly("B", "C", "A", "D");
    rows.sort(ScreenerService.comparator(ScreenSort.MARKET_CAP, SortOrder.ASC));
    assertThat(rows).extracting(ScreenResult.Row::symbol).containsExactly("B", "C", "A", "D");
    rows.sort(ScreenerService.comparator(ScreenSort.PRICE, SortOrder.ASC));
    assertThat(rows).extracting(ScreenResult.Row::symbol).containsExactly("D", "A", "B", "C");
  }

  static ScreenResult.Row row(String symbol, String price, String cap) {
    return new ScreenResult.Row(symbol, symbol, AssetType.COMMON_STOCK, "NYSE", null, new BigDecimal(price), null,
        null, null, false, null, null, cap == null ? null : new BigDecimal(cap), DataQuality.SIMULATED, null);
  }

  @Test
  void pagesFollowTheOffsetAndTheTableIsCachedForFiveSeconds() {
    AtomicLong nanos = new AtomicLong(1);
    Feed feed = new Feed();
    for (String s : List.of("AAPL", "MSFT", "F", "KO", "NVDA")) {
      feed.put(quote(s, "10.00", "0.01", 1L, false, DataQuality.SIMULATED));
    }
    ScreenerService s = service(feed, stats(null, null, false, false, nanos, 0, List.of()), true, nanos);
    ScreenQuery first = new ScreenQuery(null, null, null, null, null, null, null, null, null, null, Set.of(),
        Set.of(), ScreenSort.SYMBOL, null, 2, 0);
    ScreenResult p1 = s.screen(first);
    assertThat(symbols(p1)).containsExactly("AAPL", "F");
    assertThat(p1.nextOffset()).isEqualTo(2);
    ScreenResult p3 = s.screen(new ScreenQuery(null, null, null, null, null, null, null, null, null, null, Set.of(),
        Set.of(), ScreenSort.SYMBOL, null, 2, 4));
    assertThat(symbols(p3)).containsExactly("NVDA");
    assertThat(p3.nextOffset()).isNull();
    assertThat(p3.total()).isEqualTo(5);
    verify(feed.feed, times(1)).peek(any());                           // one table for all three screens
    nanos.addAndGet(ScreenerService.TABLE_TTL.toNanos());
    s.screen(first);
    verify(feed.feed, times(2)).peek(any());
    verify(feed.feed, never()).warm(any());                            // the SIM never warms
  }

  @Test
  void aLiveFeedIsWarmedInTheBackgroundAtMostEveryFiveMinutes() {
    AtomicLong nanos = new AtomicLong(1);
    Feed feed = new Feed().put(quote("AAPL", "227.40", "0.01", 1L, true, DataQuality.REALTIME_SINGLE_VENUE));
    ScreenerService s = service(feed, stats(null, null, true, false, nanos, 0, List.of()), false, nanos);
    ScreenResult r = s.screen(query(null));
    assertThat(r.warming()).isTrue();                                  // one symbol priced, a pass just started
    verify(feed.feed, times(1)).warm(any());
    nanos.addAndGet(Duration.ofMinutes(1).toNanos());
    assertThat(s.screen(query(null)).warming()).isFalse();             // the pass is no longer recent
    verify(feed.feed, times(1)).warm(any());
    nanos.addAndGet(ScreenerService.WARM_EVERY.toNanos());
    s.screen(query(null));
    verify(feed.feed, times(2)).warm(any());
  }

  @Test
  void queriesAreValidatedAndCursorsRoundTrip() {
    assertThat(ScreenCursor.decode(ScreenCursor.encode(25), "cursor")).isEqualTo(25);
    assertThat(ScreenCursor.decode(null, "cursor")).isZero();
    assertThatThrownBy(() -> ScreenCursor.decode("bogus!", "cursor")).isInstanceOf(ApiException.class)
        .hasMessageContaining("cursor");
    assertThatThrownBy(() -> ScreenCursor.decode(ScreenCursor.encode(ScreenCursor.MAX_OFFSET + 1), "cursor"))
        .isInstanceOf(ApiException.class);
    ScreenQuery bad = new ScreenQuery(null, new BigDecimal("50"), new BigDecimal("10"), null, null, null, null, null,
        null, null, Set.of(), Set.of(), null, null, 10, 0);
    assertThatThrownBy(() -> bad.validate(false)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.fields()).extracting(x -> x.field()).containsExactly("maxPrice"));
    assertThatThrownBy(() -> bad.validate(true)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.fields()).extracting(x -> x.field()).containsExactly("max_price"));
    ScreenQuery negative = new ScreenQuery(null, null, null, null, null, null, null, -1L, null, null, Set.of(),
        Set.of(), null, null, 10, 0);
    assertThatThrownBy(() -> negative.validate(true)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.fields()).extracting(x -> x.field()).containsExactly("min_volume"));
    ScreenQuery changes = new ScreenQuery(null, null, null, null, null, new BigDecimal("0.05"),
        new BigDecimal("-0.05"), null, null, null, Set.of(), Set.of(), null, null, 10, 0);
    assertThatThrownBy(() -> changes.validate(false)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.fields()).extracting(x -> x.field()).containsExactly("maxChangePct"));
  }

  // ------------------------------------------------------------------------------------------- sectors

  @Test
  void everyNonEtfUniverseSymbolHasASectorAndIndustriesMapToSectors() throws Exception {
    Sectors sectors = Sectors.load();
    for (Universe.Entry e : Universe.load().entries()) {
      if (e.type() != AssetType.ETF) {
        assertThat(sectors.of(e.symbol())).as(e.symbol()).isPresent();
      }
    }
    assertThat(Sectors.fromIndustry("Semiconductors")).contains(Sector.INFORMATION_TECHNOLOGY);
    assertThat(Sectors.fromIndustry("Banking")).contains(Sector.FINANCIALS);
    assertThat(Sectors.fromIndustry("Pharmaceuticals")).contains(Sector.HEALTH_CARE);
    assertThat(Sectors.fromIndustry("Real Estate")).contains(Sector.REAL_ESTATE);
    assertThat(Sectors.fromIndustry("Oil & Gas")).contains(Sector.ENERGY);
    assertThat(Sectors.fromIndustry("Something else")).isEmpty();
    assertThat(Sectors.fromIndustry(null)).isEmpty();
    assertThat(sectors.of("ZZZZ", "Biotechnology")).contains(Sector.HEALTH_CARE);
    assertThat(sectors.of("AAPL", "Banking")).contains(Sector.INFORMATION_TECHNOLOGY);   // the bundled map wins
    assertThatThrownBy(() -> Sectors.parse(new BufferedReader(new StringReader("symbol,sector\nX,Rocketry\n"))))
        .hasMessageContaining("unknown sector");
  }

  // ------------------------------------------------------------------------------------------- statistics

  /** Daily bars and profiles on demand, counting calls; a configurable failure. */
  static final class Provider implements MarketDataProvider {
    final AtomicInteger barCalls = new AtomicInteger();
    final List<String> feeds = new ArrayList<>();
    final List<String> profiled = new ArrayList<>();
    RuntimeException barsFailure;
    Function<String, Optional<CompanyProfile>> profiles = s -> Optional.of(
        new CompanyProfile(s, new BigDecimal("1000000"), new BigDecimal("50000000"), "Semiconductors", "fake"));

    @Override
    public String id() {
      return "fake";
    }

    @Override
    public Capabilities capabilities() {
      return CAPS;
    }

    @Override
    public Map<String, List<Bar>> bars(BarQuery q) {
      barCalls.incrementAndGet();
      feeds.add(q.feed());
      if (barsFailure != null) {
        throw barsFailure;
      }
      Map<String, List<Bar>> out = new HashMap<>();
      long base = "iex".equals(q.feed()) ? 1_000 : 100_000;
      for (String s : q.symbols()) {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 9, 1);
        int i = 0;
        // 25 trading-ish days up to and including 2026-10-09 (today: must be ignored, the window ends 10-08)
        while (!d.isAfter(LocalDate.of(2026, 10, 9))) {
          if (CAL.isTradingDay(d)) {
            i++;
            bars.add(new Bar(s, Timeframe.DAY1, com.stocks.common.time.Et.startOfDay(d), BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, d.equals(LocalDate.of(2026, 10, 9)) ? 999_999_999
                : base * i, null, 1, false));
          }
          d = d.plusDays(1);
        }
        out.put(s, bars);
      }
      return out;
    }

    @Override
    public Optional<CompanyProfile> profile(String symbol, RefreshPriority priority) {
      profiled.add(symbol);
      return profiles.apply(symbol);
    }
  }

  @Test
  void volumesAreTheLastTwentyDaysBeforeTodayOnBothBasesOncePerTradingDay() {
    AtomicLong nanos = new AtomicLong(1);
    Provider p = new Provider();
    ScreenerStats st = stats(p, null, true, false, nanos, 0, List.of("AAPL", "SPY"));
    assertThat(st.targetDay()).isEqualTo(LocalDate.of(2026, 10, 8));
    assertThat(st.volumesDue()).isTrue();
    st.tick();
    assertThat(p.feeds).containsExactly("sip", "iex");
    ScreenerStats.Stat aapl = st.stats().get("AAPL");
    // trading days 2026-09-01..10-08 = 27 bars (i = 1..27): the last 20 are i = 8..27, mean 17.5
    assertThat(aapl.avgVolume()).isEqualTo(1_750_000L);
    assertThat(aapl.avgVolumeIex()).isEqualTo(17_500L);
    assertThat(aapl.avgVolumeDays()).isEqualTo(20);
    assertThat(aapl.avgVolumeAsOf()).isEqualTo(LocalDate.of(2026, 10, 8));
    assertThat(st.volumesDue()).isFalse();
    st.tick();
    assertThat(p.barCalls).hasValue(2);                                    // not again the same day
    time.set(Instant.parse("2026-10-13T04:05:00Z"));                       // Tue 00:05 ET (Mon was a trading day)
    assertThat(st.volumesDue()).isTrue();
    // a reloaded copy (another process) reads the same table
    ScreenerStats other = stats(p, null, true, false, nanos, 0, List.of("AAPL", "SPY"));
    assertThat(other.stats().get("SPY").avgVolume()).isEqualTo(1_750_000L);
  }

  /**
   * Review finding: the average divided by the number of bars, so a thin IEX symbol with no IEX trade on some days
   * got an inflated average. A session without a bar now counts as zero; sessions before the first bar (a recent
   * listing, on either basis) are left out.
   */
  @Test
  void averagesCountEverySessionOfTheWindowAndSkipOnlyThoseBeforeTheListing() {
    List<LocalDate> sessions = new ArrayList<>();
    for (LocalDate d = LocalDate.of(2026, 10, 8); sessions.size() < 20; d = CAL.previousTradingDay(d)) {
      sessions.addFirst(d);
    }
    // ten bars of 1,000 on every other session: 10,000 over 20 sessions
    List<Bar> sparse = new ArrayList<>();
    for (int i = 0; i < 20; i += 2) {
      sparse.add(new Bar("X", Timeframe.DAY1, com.stocks.common.time.Et.startOfDay(sessions.get(i)), BigDecimal.ONE,
          BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1_000, null, 1, false));
    }
    assertThat(ScreenerStats.average(sparse, sessions, null)).get()
        .satisfies(a -> assertThat(a).containsExactly(500L, 20L));
    // listed five sessions ago (the SIP bars say so): the average covers those five
    List<Bar> recent = sparse.subList(8, 10);                                // sessions 16 and 18
    assertThat(ScreenerStats.average(recent, sessions, sessions.get(15))).get()
        .satisfies(a -> assertThat(a).containsExactly(400L, 5L));
    assertThat(ScreenerStats.average(List.of(), sessions, null)).isEmpty();
  }

  @Test
  void aFailedVolumePassIsRetriedAfterAnHour() {
    AtomicLong nanos = new AtomicLong(1);
    Provider p = new Provider();
    p.barsFailure = new ProviderException("fake", 500, "boom", null, null);
    ScreenerStats st = stats(p, null, false, false, nanos, 0, List.of("AAPL"));
    st.tick();
    st.tick();
    assertThat(p.barCalls).hasValue(1);
    p.barsFailure = null;
    nanos.addAndGet(ScreenerStats.RETRY_AFTER.toNanos());
    st.tick();
    assertThat(p.barCalls).hasValue(2);
    assertThat(st.stats().get("AAPL").avgVolume()).isEqualTo(1_750_000L);
    assertThat(st.stats().get("AAPL").avgVolumeIex()).isNull();            // no IEX basis off hybrid
  }

  @Test
  void profilesArriveAFewPerTickStocksOnlyNeverFetchedFirstAndEmptyAnswersAreStored() {
    AtomicLong nanos = new AtomicLong(1);
    Provider p = new Provider();
    p.profiles = s -> s.equals("F") ? Optional.empty() : Optional.of(new CompanyProfile(s,
        new BigDecimal("1000000"), null, "Semiconductors", "fake"));
    ScreenerStats st = stats(new Provider(), p, false, false, nanos, 2, List.of("SPY", "AAPL", "F", "KO", "NVDA"));
    st.refreshProfiles(2);
    assertThat(p.profiled).containsExactly("AAPL", "F");                   // alphabetical; SPY is an ETF
    assertThat(st.stats().get("AAPL").sharesOutstanding()).isEqualByComparingTo("1000000");
    assertThat(st.stats().get("AAPL").industry()).isEqualTo("Semiconductors");
    assertThat(st.stats().get("F").profileAt()).isEqualTo(time.now());      // empty answer: stored, not retried
    assertThat(st.stats().get("F").sharesOutstanding()).isNull();
    st.refreshProfiles(2);
    assertThat(p.profiled).containsExactly("AAPL", "F", "KO", "NVDA");
    st.refreshProfiles(2);
    assertThat(p.profiled).hasSize(4);                                     // all fresh for 30 days
    time.advance(ScreenerStats.PROFILE_TTL);
    st.refreshProfiles(1);
    assertThat(p.profiled).hasSize(5);
  }

  @Test
  void profileFailuresAreRetriedLaterAndARefusalTurnsProfilesOffForADay() {
    AtomicLong nanos = new AtomicLong(1);
    Provider p = new Provider();
    p.profiles = s -> {
      throw new ProviderException("fake", s.equals("AAPL") ? 500 : 403, "no", null, null);
    };
    ScreenerStats st = stats(new Provider(), p, false, false, nanos, 3, List.of("AAPL", "F", "KO"));
    st.refreshProfiles(3);
    assertThat(p.profiled).containsExactly("AAPL", "F");                   // AAPL failed, F refused: stop
    st.refreshProfiles(3);
    assertThat(p.profiled).hasSize(2);                                     // off for a day
    nanos.addAndGet(ScreenerStats.FORBIDDEN_FOR.toNanos());
    p.profiles = s -> Optional.empty();
    st.refreshProfiles(3);
    assertThat(p.profiled).containsExactly("AAPL", "F", "AAPL", "F", "KO");   // AAPL's hour passed too
    // a throttled call (the shared budget is low) stops the tick without marking anything
    Provider throttled = new Provider();
    throttled.profiles = s -> {
      throw new ProviderException("fake", 429, "budget", null, null);
    };
    ScreenerStats t = stats(new Provider(), throttled, false, false, nanos, 3, List.of("MSFT", "NVDA"));
    t.refreshProfiles(3);
    t.refreshProfiles(3);
    assertThat(throttled.profiled).containsExactly("MSFT", "MSFT");
  }

  @Test
  void theSimComputesEverythingInOneBackgroundPass() throws Exception {
    AtomicLong nanos = new AtomicLong(1);
    Provider p = new Provider();
    ScreenerStats st = stats(p, p, false, true, nanos, 0, List.of("AAPL", "SPY", "F"));
    st.ensureLocal();
    com.stocks.marketdata.testing.Await.until(() -> st.stats().size() == 3 && !st.refreshing(),
        "the SIM statistics pass");
    assertThat(p.feeds).containsExactly((String) null);                    // the SIM's own bars, no feed
    assertThat(p.profiled).containsExactly("AAPL", "F");
    assertThat(st.ensureLocal()).isFalse();                                // once
  }

  @Test
  void theFirstSimScreenIsWarmingAndNewStatisticsRebuildTheTableAtOnce() throws Exception {
    AtomicLong nanos = new AtomicLong(1);
    Provider p = new Provider();
    ScreenerStats st = stats(p, p, false, true, nanos, 0, List.of("AAPL"));
    Feed feed = new Feed().put(quote("AAPL", "227.40", "0.01", 1_000_000L, false, DataQuality.SIMULATED));
    ScreenerService s = service(feed, st, true, nanos);
    ScreenResult first = s.screen(query(null));
    // the pass was just started (its thread may not have begun yet): warming, no statistics in this table
    assertThat(first.warming()).isTrue();
    assertThat(first.rows().get(0).avgVolume()).isNull();
    com.stocks.marketdata.testing.Await.until(() -> st.stats().containsKey("AAPL") && !st.refreshing(),
        "the SIM statistics pass");
    // well inside the 5 s table cache, yet the new snapshot is used right away
    ScreenResult next = s.screen(query(null));
    assertThat(next.warming()).isFalse();
    assertThat(next.rows().get(0).avgVolume()).isNotNull();
    assertThat(next.coverage().withAvgVolume()).isEqualTo(1);
    verify(feed.feed, times(2)).peek(any());
    s.screen(query(null));
    verify(feed.feed, times(2)).peek(any());                               // unchanged statistics: cached
  }

  @Test
  void repositoryMergesKeepTheOtherHalfOfTheRowAndBindNulls() {
    Instant t = time.now();
    repo.upsertVolumes(Map.of("AAPL", new ScreenerStatRepository.Volumes(null, null, null, null)), t);
    repo.upsertProfile("AAPL", new ScreenerStatRepository.Profile(null, null, null, null), t);
    repo.upsertProfile("MSFT", new ScreenerStatRepository.Profile(new BigDecimal("7430000000.4"),
        new BigDecimal("0"), "x".repeat(150), "finnhub"), t);
    repo.upsertVolumes(Map.of("MSFT", new ScreenerStatRepository.Volumes(20_000_000L, 400_000L, 20,
        LocalDate.of(2026, 10, 8))), t.plusSeconds(60));
    repo.upsertProfile("ZZZ", new ScreenerStatRepository.Profile(new BigDecimal("0.4"), null, " ", "finnhub"), t);
    Map<String, ScreenerStatRepository.Row> rows = new HashMap<>();
    repo.loadAll().forEach(r -> rows.put(r.symbol(), r));
    assertThat(rows.get("AAPL").avgVolume()).isNull();
    assertThat(rows.get("AAPL").profileAt()).isEqualTo(t);
    ScreenerStatRepository.Row msft = rows.get("MSFT");
    assertThat(msft.sharesOutstanding()).isEqualByComparingTo("7430000000");
    assertThat(msft.marketCap()).isNull();                                  // zero means unknown
    assertThat(msft.industry()).hasSize(100);
    assertThat(msft.profileSource()).isEqualTo("finnhub");
    assertThat(msft.avgVolume()).isEqualTo(20_000_000L);
    assertThat(msft.avgVolumeIex()).isEqualTo(400_000L);
    assertThat(msft.avgVolumeAsOf()).isEqualTo(LocalDate.of(2026, 10, 8));
    assertThat(msft.volumeAt()).isEqualTo(t.plusSeconds(60));
    assertThat(msft.updatedAt()).isEqualTo(t.plusSeconds(60));
    assertThat(rows.get("ZZZ").sharesOutstanding()).isNull();               // rounds to 0: not a share count
    assertThat(rows.get("ZZZ").industry()).isNull();
  }
}
