package com.stocks.marketdata.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.calendar.InMemoryCalendars;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.MoverList;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.Et;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.JacksonConfig;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.marketdata.core.Universe;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.testkit.GoldenFiles;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** §15.7: the SIM is a pure function of (seed, symbol, time) over the real calendar. */
class SimulatedMarketDataProviderTest {

  static final Instant T = Instant.parse("2026-10-09T14:30:00Z");               // Fri 10:30 ET
  static final MarketCalendar CAL = InMemoryCalendars.bundled();
  static final Universe UNIVERSE = Universe.load();

  static SimulatedMarketDataProvider sim(long seed, Instant now, SimScenarios scenarios) {
    return new SimulatedMarketDataProvider(seed, CAL, UNIVERSE, scenarios, new MutableTimeSource(now));
  }

  @Test
  void goldenSeed42() {
    SimulatedMarketDataProvider p = sim(42, T, SimScenarios.none());
    Quote q = p.quoteAt("AAPL", T).orElseThrow();
    assertThat(q.bid()).isEqualByComparingTo("250.47");
    assertThat(q.ask()).isEqualByComparingTo("250.53");
    assertThat(q.quality()).isEqualTo(DataQuality.SIMULATED);
    assertThat(p.officialClose("AAPL", LocalDate.of(2026, 10, 9))).contains(new BigDecimal("255.0000"));
    Map<String, Object> golden = new LinkedHashMap<>();
    for (String s : List.of("AAPL", "SPY", "NVDA", "SIMA")) {
      Quote x = p.quoteAt(s, T).orElseThrow();
      Trade t = p.lastTradeAt(s, T).orElseThrow();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("bid", x.bid());
      m.put("bidSize", x.bidSize());
      m.put("ask", x.ask());
      m.put("askSize", x.askSize());
      m.put("quoteTs", x.ts());
      m.put("last", t.price());
      m.put("lastSize", t.size());
      m.put("lastTs", t.ts());
      m.put("close20261008", p.officialClose(s, LocalDate.of(2026, 10, 8)).orElseThrow());
      m.put("close20261009", p.officialClose(s, LocalDate.of(2026, 10, 9)).orElseThrow());
      golden.put(s, m);
    }
    GoldenFiles.assertMatches("sim", "seed42-2026-10-09T1430Z", JacksonConfig.newMapper().writeValueAsString(golden));
  }

  @Test
  void deterministicPerSeed() {
    SimulatedMarketDataProvider a = sim(42, T, SimScenarios.none());
    SimulatedMarketDataProvider b = sim(42, T, SimScenarios.none());
    SimulatedMarketDataProvider c = sim(7, T, SimScenarios.none());
    for (String s : List.of("AAPL", "MSFT", "SIMQ", "XLE")) {
      assertThat(a.quoteAt(s, T)).isEqualTo(b.quoteAt(s, T));
      assertThat(a.tradesBetween(s, T.minusSeconds(60), T, 1000)).isEqualTo(b.tradesBetween(s, T.minusSeconds(60), T,
          1000));
    }
    assertThat(a.quoteAt("MSFT", T)).isNotEqualTo(c.quoteAt("MSFT", T));
    assertThat(a.knows("ZZZZ")).isFalse();
    assertThat(a.quoteAt("ZZZZ", T)).isEmpty();
  }

  @Test
  void liveMinuteBarsEqualHistoricalBars() {
    SimulatedMarketDataProvider history = sim(42, Instant.parse("2026-10-10T12:00:00Z"), SimScenarios.none());
    TradingDay day = CAL.day(LocalDate.of(2026, 10, 9)).orElseThrow();
    Random random = new Random(1234);
    for (String symbol : List.of("AAPL", "SIMA", "KO")) {
      Map<String, List<Bar>> bars = history.bars(new BarQuery(Set.of(symbol), Timeframe.MIN1, day.preOpenAt(),
          day.postCloseAt(), AdjustmentMode.RAW, true, 100_000, null));
      Map<Instant, Bar> byStart = new LinkedHashMap<>();
      bars.get(symbol).forEach(b -> byStart.put(b.start(), b));
      for (int i = 0; i < 25; i++) {
        long offset = random.nextLong(Duration.between(day.preOpenAt(), day.postCloseAt()).toMinutes());
        Instant minute = day.preOpenAt().plus(offset, ChronoUnit.MINUTES);
        // the "live" view one second after the minute ends
        SimulatedMarketDataProvider live = sim(42, minute.plusSeconds(61), SimScenarios.none());
        assertThat(live.minuteBar(symbol, minute, minute.plusSeconds(61)).orElse(null)).as("%s %s", symbol, minute)
            .isEqualTo(byStart.get(minute));
      }
    }
  }

  @Test
  void dailyBarsAgreeWithClosesAndExtremes() {
    SimulatedMarketDataProvider p = sim(42, Instant.parse("2026-10-10T12:00:00Z"), SimScenarios.none());
    LocalDate d = LocalDate.of(2026, 10, 9);
    Bar daily = p.bars(new BarQuery(Set.of("AAPL"), Timeframe.DAY1, Et.startOfDay(d), Et.startOfDay(d.plusDays(1)),
        AdjustmentMode.RAW, false, 10, null)).get("AAPL").getFirst();
    assertThat(daily.close()).isEqualTo(p.officialClose("AAPL", d).orElseThrow());
    assertThat(daily.start()).isEqualTo(Et.startOfDay(d));
    // Daily extremes are sampled from the price path (§15.7), so the 1Min prints may poke a few cents past them.
    TradingDay td = CAL.day(d).orElseThrow();
    BigDecimal slack = daily.close().multiply(new BigDecimal("0.003"));
    for (Trade t : p.tradesBetween("AAPL", td.rthOpenAt(), td.rthCloseAt().minusNanos(1), 100_000)) {
      assertThat(t.price()).isBetween(daily.low().subtract(slack), daily.high().add(slack));
    }
    List<Bar> weekly = p.bars(new BarQuery(Set.of("AAPL"), Timeframe.WEEK1, Instant.parse("2026-09-01T00:00:00Z"),
        Instant.parse("2026-10-10T00:00:00Z"), AdjustmentMode.SPLIT, false, 100, null)).get("AAPL");
    assertThat(weekly.getLast().close()).isEqualTo(daily.close());
    assertThat(weekly).allSatisfy(b -> assertThat(Et.date(b.start()).getDayOfWeek().getValue()).isLessThanOrEqualTo(5));
  }

  @Test
  void quotesFreezeWhileClosedAndFollowTheTierSpread() {
    SimulatedMarketDataProvider p = sim(42, T, SimScenarios.none());
    Instant saturday = Instant.parse("2026-10-10T16:00:00Z");
    Instant lastPost = CAL.day(LocalDate.of(2026, 10, 9)).orElseThrow().postCloseAt().minusNanos(1000);
    assertThat(p.quoteAt("AAPL", saturday)).isEqualTo(p.quoteAt("AAPL", lastPost));
    assertThat(p.tradesBetween("AAPL", lastPost.plusSeconds(1), saturday, 10)).isEmpty();
    for (String s : List.of("AAPL", "MSFT", "SIMB")) {
      Quote q = p.quoteAt(s, T).orElseThrow();
      BigDecimal mid = q.bid().add(q.ask()).divide(BigDecimal.TWO, 6, RoundingMode.HALF_EVEN);
      BigDecimal bps = q.ask().subtract(q.bid()).multiply(BigDecimal.valueOf(10_000)).divide(mid, 2,
          RoundingMode.HALF_EVEN);
      assertThat(bps).as(s).isLessThanOrEqualTo(new BigDecimal("10"));        // MEGA: inside the IEX limit
      assertThat(q.twoSided()).isTrue();
    }
    Quote pre = p.quoteAt("AAPL", Instant.parse("2026-10-09T12:00:00Z")).orElseThrow();   // 08:00 ET, PRE
    Quote reg = p.quoteAt("AAPL", T).orElseThrow();
    assertThat(pre.ask().subtract(pre.bid())).isGreaterThan(reg.ask().subtract(reg.bid()).subtract(
        new BigDecimal("0.02")));
  }

  @Test
  void universeAndTestSymbols() {
    assertThat(UNIVERSE.size()).isBetween(550, 800);
    assertThat(UNIVERSE.get("AAPL").orElseThrow().tier()).isEqualTo(LiquidityTier.MEGA);
    assertThat(UNIVERSE.get("SPY").orElseThrow().type()).hasToString("ETF");
    SimulatedMarketDataProvider p = sim(42, T, SimScenarios.none());
    List<Asset> assets = p.assets();
    assertThat(assets).filteredOn(a -> a.symbol().startsWith("SIM") && a.symbol().length() == 4).hasSize(26)
        .allSatisfy(a -> {
          assertThat(a.fractionable()).isTrue();
          assertThat(a.tier()).isEqualTo(LiquidityTier.MEGA);
        });
    assertThat(p.movers(MoverList.GAINERS, 5).items()).hasSize(5)
        .allSatisfy(m -> assertThat(m.changePct()).isPositive());
    assertThat(p.fundamentals("AAPL")).hasValueSatisfying(f -> assertThat(f.week52High()).isNotNull());
    assertThat(p.news(Set.of("AAPL"), 3, null)).hasSize(3);
  }

  @Test
  void scenariosLoadAndApply() {
    SimScenarios sc = SimScenarios.load(List.of("halt-resume", "gap-through-stop", "feed-blackout",
        "one-sided-quote", "split-2-for-1", "cash-dividend", "flash-crash"));
    assertThat(sc.all()).hasSize(7);
    SimulatedMarketDataProvider p = sim(42, T, sc);
    LocalDate d = LocalDate.of(2026, 10, 9);
    Instant halt = Et.at(d, LocalTime.of(11, 5));
    assertThat(p.halted("SIMH", halt)).isTrue();
    assertThat(p.halted("SIMH", Et.at(d, LocalTime.of(11, 10)))).isFalse();
    assertThat(p.halted("AAPL", halt)).isFalse();
    assertThat(p.quoteAt("SIMH", halt)).isEqualTo(p.quoteAt("SIMH", Et.at(d, LocalTime.of(11, 0)).minusNanos(1000)));
    Instant blackout = Et.at(d, LocalTime.of(13, 2));
    assertThat(p.confirmedAt("SIMB", blackout)).isEqualTo(Et.at(d, LocalTime.of(13, 0)));
    assertThat(p.confirmedAt("AAPL", blackout)).isEqualTo(blackout);
    assertThat(p.quoteAt("SIMO", Et.at(d, LocalTime.of(10, 20))).orElseThrow().twoSided()).isFalse();
    BigDecimal before = p.officialClose("SIMS", LocalDate.of(2026, 10, 13)).orElseThrow();
    BigDecimal after = p.officialClose("SIMS", LocalDate.of(2026, 10, 14)).orElseThrow();
    assertThat(before.divide(after, 2, RoundingMode.HALF_EVEN)).isBetween(new BigDecimal("1.6"),
        new BigDecimal("2.5"));
    assertThat(p.corporateActions(Set.of(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))).hasSize(2);
    BigDecimal normal = p.priceAt("SIMF", Et.at(d, LocalTime.of(13, 59))).orElseThrow();
    BigDecimal crash = p.priceAt("SIMF", Et.at(d, LocalTime.of(14, 5))).orElseThrow();
    assertThat(crash).isLessThan(normal.multiply(new BigDecimal("0.95")));
  }
}
