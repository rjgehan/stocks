package com.stocks.league;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.portfolio.dto.LeaderboardDto;
import com.stocks.contract.portfolio.dto.LeaderboardRowDto;
import com.stocks.contract.portfolio.dto.PlayerStatsDto;
import com.stocks.contract.portfolio.dto.RaceChartDto;
import com.stocks.contract.portfolio.dto.RaceChartDto.RacePointDto;
import com.stocks.contract.portfolio.dto.RaceChartDto.RaceSeriesDto;
import com.stocks.portfolio.MetricsCalculator;
import com.stocks.portfolio.MetricsCalculator.UnitPoint;
import com.stocks.portfolio.PortfolioData;
import com.stocks.portfolio.PortfolioKit;
import com.stocks.testkit.Fixtures.AccountRef;
import com.stocks.testkit.Fixtures.PlayerRef;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §16.8 / §16.9 end to end on a fresh database (Mon–Fri fallback calendar, 20:00 ET cut-off): the race chart is the
 * board's unit-value curves normalized at the period start (a deposit never moves a line; the last point is the
 * row's return), TODAY uses the 5-minute samples, SPY rides along; player stats agree with the board, show closed
 * lots with the feed delay for other players, rank history from {@code leaderboard_snapshot}, and Sharpe once there
 * are 20 daily returns.
 */
class LeagueStatsTest {

  static final Instant MON_10 = Instant.parse("2026-10-05T14:00:00Z");
  static final Instant MON_EOD = Instant.parse("2026-10-06T00:20:00Z");
  static final Instant TUE_11 = Instant.parse("2026-10-06T15:00:00Z");
  static final Instant TUE_EOD = Instant.parse("2026-10-07T00:20:00Z");
  static final Instant WED_10 = Instant.parse("2026-10-07T14:00:00Z");
  static final Instant WED_11 = Instant.parse("2026-10-07T15:00:00Z");
  static final LocalDate FRI_BEFORE = LocalDate.of(2026, 10, 2);
  static final LocalDate MON = LocalDate.of(2026, 10, 5);
  static final LocalDate TUE = LocalDate.of(2026, 10, 6);

  LeagueKit lk;
  PortfolioKit kit;
  PortfolioData data;

  @AfterEach
  void closeDb() {
    kit.close();
  }

  @BeforeEach
  void setUp() {
    lk = new LeagueKit();
    kit = lk.kit;
    data = kit.data;
    kit.at(MON_10);
  }

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  RaceChartDto race(LeaderboardPeriod period, KindFilter kind) {
    lk.leaderboard.refresh();
    return lk.stats.race(period, kind);
  }

  static RaceSeriesDto series(RaceChartDto chart, String handle) {
    return chart.series().stream().filter(s -> s.player().handle().equals(handle)).findFirst().orElseThrow();
  }

  static List<BigDecimal> returns(List<RacePointDto> points) {
    return points.stream().map(RacePointDto::ret).toList();
  }

  LeaderboardRowDto row(LeaderboardPeriod period, String handle) {
    LeaderboardDto board = lk.leaderboard.board(period, LeaderboardMetric.RETURN, KindFilter.ALL, 100, null);
    return board.rows().stream().filter(r -> r.player().handle().equals(handle)).findFirst().orElseThrow();
  }

  // ------------------------------------------------------------------------------------------- race chart

  @Test
  void theRaceNormalizesEveryLineAtThePeriodStartAndADepositNeverMovesALine() {
    PlayerRef alice = data.human("alice");
    PlayerRef bob = data.human("bob");
    PlayerRef carol = data.human("carol");
    AccountRef a = data.open(alice, "500.00");
    AccountRef b = data.open(bob, "500.00");
    AccountRef c = data.open(carol, "500.00");
    data.buy(a, "SIMA", "5", "100.00");
    data.buy(b, "SIMB", "5", "100.00");
    data.buy(c, "SIMB", "5", "100.00");
    kit.benchmark.write("SPY", FRI_BEFORE, d("500.0000"), BigDecimal.ZERO);
    kit.market.dayEndMark("SIMA", MON, "110.00").dayEndMark("SIMB", MON, "105.00").dayEndMark("SPY", MON, "505.00");
    kit.at(MON_EOD);
    kit.eodJob().runFor(MON);

    // Tuesday 11:00: bob gets a $1,000 deposit at SIMB 108 (U = 1.08 before and after)
    kit.market.print("SIMB", TUE_11.minusSeconds(60), "108.00", 10);
    kit.at(TUE_11);
    data.deposit(b, "1000.00");
    kit.market.dayEndMark("SIMA", TUE, "112.00").dayEndMark("SIMB", TUE, "108.00").dayEndMark("SPY", TUE, "510.00");
    kit.at(TUE_EOD);
    kit.eodJob().runFor(TUE);

    kit.market.print("SIMA", WED_11.minusSeconds(60), "118.00", 10).print("SIMB", WED_11.minusSeconds(60), "100.00", 10)
        .print("SPY", WED_11.minusSeconds(60), "515.00", 10);
    kit.at(WED_11);
    RaceChartDto week = race(LeaderboardPeriod.WEEK, KindFilter.ALL);

    assertThat(week.period()).isEqualTo(LeaderboardPeriod.WEEK);
    assertThat(week.series()).extracting(s -> s.player().handle()).containsExactly("alice", "bob", "carol");
    RaceSeriesDto al = series(week, "alice");
    RaceSeriesDto bo = series(week, "bob");
    RaceSeriesDto ca = series(week, "carol");
    assertThat(al.partialPeriod()).as("opened after the WEEK anchor: starts at its opening").isTrue();
    assertThat(al.points().get(0).t()).isEqualTo(MON_10);
    assertThat(returns(al.points())).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("0"), d("0.1"), d("0.12"), d("0.18"));
    // bob's $1,000 deposit is invisible: his line follows carol's (same holding) until the deposit's cash dilutes
    // Wednesday's drop, and he never shows the naive +200%
    assertThat(returns(bo.points()).subList(0, 3)).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("0"), d("0.05"), d("0.08"));
    assertThat(returns(bo.points()).subList(0, 3)).usingElementComparator(BigDecimal::compareTo)
        .isEqualTo(returns(ca.points()).subList(0, 3));
    assertThat(bo.points().get(3).ret()).isEqualByComparingTo("0.0519480519");
    assertThat(ca.points().get(3).ret()).isEqualByComparingTo("0");
    for (RaceSeriesDto s : week.series()) {
      assertThat(s.points().get(s.points().size() - 1).ret()).as("%s ends at its board return", s.player().handle())
          .isEqualByComparingTo(row(LeaderboardPeriod.WEEK, s.player().handle()).ret())
          .isEqualByComparingTo(s.ret());
      assertThat(s.points().get(s.points().size() - 1).t()).isEqualTo(WED_11);
    }
    assertThat(week.benchmark().symbol()).isEqualTo("SPY");
    assertThat(returns(week.benchmark().points())).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("0"), d("0.01"), d("0.02"), d("0.03"));
    assertThat(week.benchmark().ret()).isEqualByComparingTo("0.03");
    assertThat(week.periodStart()).isEqualTo(Instant.parse("2026-10-03T00:00:00Z"));    // Fri 20:00 ET
    // pinned (golden performance/race_deposit.json): every line's normalized points and SPY's
    java.util.Map<String, Object> golden = new java.util.LinkedHashMap<>();
    golden.put("description", "§16.9 race chart, WEEK: alice and carol hold one stock each; bob holds carol's stock "
        + "and receives a $1,000 deposit on Tuesday at U = 1.08. Lines are U(t) / U(a) - 1: bob's line follows "
        + "carol's through the deposit (TWR unaffected); only Wednesday's drop is diluted by the idle cash.");
    for (RaceSeriesDto s : week.series()) {
      golden.put(s.player().handle(), s.points().stream().map(p -> p.t() + " " + p.ret().toPlainString()).toList());
    }
    golden.put("SPY", week.benchmark().points().stream().map(p -> p.t() + " " + p.ret().toPlainString()).toList());
    com.stocks.testkit.GoldenFiles.assertMatches("performance", "race_deposit", golden);

    // the cache: same board → same chart, a new board → a new chart. The board itself is refreshed on wall-clock time
    // (leaderboard-refresh, 5 s), so the identity check only runs while no refresh happened in between (a slow CI
    // machine can take longer than that between two statements)
    LeaderboardComputer.Board board = lk.leaderboard.refresh();
    RaceChartDto cached = lk.stats.race(LeaderboardPeriod.WEEK, KindFilter.ALL);
    RaceChartDto again = lk.stats.race(LeaderboardPeriod.WEEK, KindFilter.ALL);
    if (lk.leaderboard.current() == board) {
      assertThat(again).isSameAs(cached).isNotSameAs(week);
    }
    lk.leaderboard.refresh();
    assertThat(lk.stats.race(LeaderboardPeriod.WEEK, KindFilter.ALL)).isNotSameAs(cached).isEqualTo(cached);

    // kinds
    data.fx.status(carol, "DISABLED");
    assertThat(race(LeaderboardPeriod.WEEK, KindFilter.HUMAN).series()).extracting(s -> s.player().handle())
        .containsExactly("alice", "bob");
    assertThat(lk.stats.race(LeaderboardPeriod.WEEK, KindFilter.AI).series()).isEmpty();

    // ALL: every line from its own opening; SPY from the league's first opening (no recorded SPY mark: no curve)
    RaceChartDto all = lk.stats.race(LeaderboardPeriod.ALL, KindFilter.ALL);
    assertThat(returns(series(all, "alice").points())).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("0"), d("0.1"), d("0.12"), d("0.18"));
    assertThat(all.periodStart()).isEqualTo(MON_10);
    assertThat(all.benchmark().points()).isEmpty();
    assertThat(all.benchmark().ret()).isNull();

    // TODAY: Tuesday's EOD, then the 5-minute samples, then live
    kit.market.print("SIMA", WED_10.minusSeconds(60), "115.00", 10);
    kit.at(WED_10);
    kit.sampler.sample(WED_10);
    kit.market.print("SIMA", WED_11.minusSeconds(30), "118.00", 10);
    kit.at(WED_11);
    RaceChartDto today = race(LeaderboardPeriod.TODAY, KindFilter.ALL);
    RaceSeriesDto t = series(today, "alice");
    assertThat(t.points()).extracting(RacePointDto::t).containsExactly(Instant.parse("2026-10-07T00:00:00Z"),
        WED_10, WED_11);
    assertThat(t.points().get(1).ret()).isEqualByComparingTo(
        d("115").divide(d("112"), 18, java.math.RoundingMode.HALF_EVEN).subtract(BigDecimal.ONE)
            .setScale(10, java.math.RoundingMode.HALF_EVEN));
    assertThat(t.points().get(2).ret()).isEqualByComparingTo(row(LeaderboardPeriod.TODAY, "alice").ret());
  }

  @Test
  void normalizationLeavesOutUndefinedPointsAndMarksARebase() {
    List<LeagueEntry.CurvePoint> curve = List.of(
        new LeagueEntry.CurvePoint(MON_10, null, d("2"), 1),
        new LeagueEntry.CurvePoint(MON_EOD, MON, d("3"), 1),
        new LeagueEntry.CurvePoint(TUE_EOD, TUE, d("1"), 2));
    assertThat(returns(PlayerStatsService.normalize(curve))).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("0"), d("0.5"), d("-1"));
    assertThat(PlayerStatsService.normalize(List.of(new LeagueEntry.CurvePoint(MON_10, null, BigDecimal.ZERO, 1))))
        .isEmpty();
    assertThat(PlayerStatsService.normalize(List.of())).isEmpty();
  }

  // ------------------------------------------------------------------------------------------- stats

  @Test
  void statsAgreeWithTheBoardAndHideOtherPlayersFreshTrades() {
    PlayerRef alice = data.human("alice");
    PlayerRef bob = data.human("bob");
    AccountRef a = data.open(alice, "500.00");
    AccountRef b = data.open(bob, "500.00");
    data.buy(a, "SIMA", "5", "100.00");
    data.buy(b, "SIMB", "5", "100.00");
    kit.market.dayEndMark("SIMA", MON, "110.00").dayEndMark("SIMB", MON, "105.00");
    kit.at(MON_EOD);
    kit.eodJob().runFor(MON);
    lk.snapshotWriter.write(MON);

    kit.market.print("SIMA", TUE_11.minusSeconds(60), "112.00", 10).print("SIMB", TUE_11.minusSeconds(60), "90.00", 10);
    kit.at(TUE_11);
    data.sell(a, "SIMA", "2", "112.00");                 // realized +24.00 after 25 h
    data.sell(b, "SIMB", "1", "90.00");                  // realized −10.00
    kit.at(TUE_11.plus(Duration.ofMinutes(5)));
    lk.leaderboard.refresh();

    PlayerStatsDto mine = lk.stats.myStats(alice.id(), LeaderboardPeriod.TODAY);
    LeaderboardRowDto row = row(LeaderboardPeriod.TODAY, "alice");
    assertThat(mine.ret()).isEqualByComparingTo(row.ret());
    assertThat(mine.pnl()).isEqualByComparingTo(row.pnl());
    assertThat(mine.rank()).isEqualTo(row.rank()).isEqualTo(1);
    assertThat(mine.rankChange()).isEqualTo(0);
    assertThat(mine.periodStart()).isEqualTo(Instant.parse("2026-10-06T00:00:00Z"));   // Mon 20:00 ET
    assertThat(mine.closingSells()).isEqualTo(1);
    assertThat(mine.wins()).isEqualTo(1);
    assertThat(mine.winRate()).isEqualByComparingTo("1");
    assertThat(mine.grossProfit()).isEqualByComparingTo("24.00");
    assertThat(mine.grossLoss()).isEqualByComparingTo("0.00");
    assertThat(mine.profitFactor()).as("no losing sell").isNull();
    assertThat(mine.closedLots()).isEqualTo(1);
    assertThat(mine.avgHoldSeconds()).isEqualTo(Duration.ofHours(25).toSeconds());
    assertThat(mine.tradesAsOf()).isEqualTo(mine.asOf());
    assertThat(mine.bestTrade()).isNotNull().satisfies(l -> {
      assertThat(l.symbol()).isEqualTo("SIMA");
      assertThat(l.qty()).isEqualByComparingTo("2");
      assertThat(l.cost()).isEqualByComparingTo("200.00");
      assertThat(l.proceeds()).isEqualByComparingTo("224.00");
      assertThat(l.realizedPnl()).isEqualByComparingTo("24.00");
      assertThat(l.ret()).isEqualByComparingTo("0.12");
    });
    assertThat(mine.worstTrade()).isEqualTo(mine.bestTrade());
    assertThat(mine.sharpe()).isNull();
    assertThat(mine.dailyReturns()).isEqualTo(0);
    assertThat(mine.sharpeMinDays()).isEqualTo(20);
    assertThat(mine.rankHistory()).singleElement().satisfies(p -> {
      assertThat(p.date()).isEqualTo(MON);
      assertThat(p.rank()).isEqualTo(1);
      assertThat(p.ranked()).isEqualTo(2);
    });

    // bob looking at alice 5 minutes after her sell: counted, but not shown before the 15-minute feed delay
    PlayerStatsDto seen = lk.stats.playerStats("alice", LeaderboardPeriod.TODAY, bob.id());
    assertThat(seen.closedLots()).isEqualTo(1);
    assertThat(seen.bestTrade()).isNull();
    assertThat(seen.worstTrade()).isNull();
    assertThat(seen.tradesAsOf()).isEqualTo(seen.asOf().minus(Duration.ofMinutes(15)));
    assertThat(lk.stats.playerStats("alice", LeaderboardPeriod.TODAY, alice.id()).bestTrade()).isNotNull();
    kit.at(TUE_11.plus(Duration.ofMinutes(16)));
    lk.leaderboard.refresh();
    assertThat(lk.stats.playerStats("alice", LeaderboardPeriod.TODAY, bob.id()).bestTrade()).isNotNull();

    PlayerStatsDto bobs = lk.stats.myStats(bob.id(), LeaderboardPeriod.TODAY);
    assertThat(bobs.grossLoss()).isEqualByComparingTo("10.00");
    assertThat(bobs.profitFactor()).isEqualByComparingTo("0");
    assertThat(bobs.winRate()).isEqualByComparingTo("0");
    assertThat(bobs.worstTrade().realizedPnl()).isEqualByComparingTo("-10.00");

    // WEEK: the lots closed since Friday's EOD; ALL defaults; unknown and account-less players
    assertThat(lk.stats.myStats(alice.id(), null).period()).isEqualTo(LeaderboardPeriod.ALL);
    assertThat(lk.stats.myStats(alice.id(), LeaderboardPeriod.WEEK).closedLots()).isEqualTo(1);
    assertThatThrownBy(() -> lk.stats.playerStats("nobody", LeaderboardPeriod.ALL, bob.id()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
    PlayerRef dave = data.human("dave");
    assertThatThrownBy(() -> lk.stats.playerStats("dave", LeaderboardPeriod.ALL, bob.id()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
    assertThatThrownBy(() -> lk.stats.myStats(dave.id(), LeaderboardPeriod.ALL))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
  }

  @Test
  void sharpeNeedsTwentyDailyReturnsAndMatchesTheEodUnitValues() {
    LocalDate day = LocalDate.of(2026, 8, 31);                   // a Monday
    kit.at(Instant.parse("2026-08-31T14:00:00Z"));
    PlayerRef alice = data.human("alice");
    AccountRef a = data.open(alice, "500.00");
    data.buy(a, "SIMA", "5", "100.00");
    String[] closes = {"101.00", "99.50", "100.20", "102.40", "101.10", "103.00", "104.25", "103.10", "105.00",
        "106.30", "105.90", "107.20", "108.00", "106.40", "107.70", "109.10", "108.30", "110.00", "111.25", "110.40",
        "112.10"};
    List<UnitPoint> path = new ArrayList<>();
    path.add(new UnitPoint(BigDecimal.ONE, 1));
    for (int i = 0; i < closes.length; i++) {
      kit.market.dayEndMark("SIMA", day, closes[i]);
      kit.at(kit.pnlCalendar.cutoff(day).plus(Duration.ofMinutes(20)));
      kit.eodJob().runFor(day);
      path.add(new UnitPoint(kit.eodRepository.find(a.id(), day).orElseThrow().unitValue(), 1));
      if (i == 18) {                                          // 19 daily returns: not yet
        lk.leaderboard.refresh();
        assertThat(row(LeaderboardPeriod.ALL, "alice").sharpe()).isNull();
        assertThat(lk.stats.myStats(alice.id(), LeaderboardPeriod.ALL).dailyReturns()).isEqualTo(19);
      }
      day = kit.pnlCalendar.nextTradingDay(day);
    }
    kit.market.print("SIMA", kit.time.now().plus(Duration.ofHours(10)), "113.00", 10);
    kit.at(kit.time.now().plus(Duration.ofHours(10)));
    lk.leaderboard.refresh();
    MetricsCalculator.RiskStats expected = MetricsCalculator.riskOfPath(path);
    assertThat(expected.dailyReturns()).isEqualTo(21);
    PlayerStatsDto all = lk.stats.myStats(alice.id(), LeaderboardPeriod.ALL);
    assertThat(all.dailyReturns()).as("the live point is not a daily return").isEqualTo(21);
    assertThat(all.sharpe()).isNotNull().isEqualByComparingTo(expected.sharpe());
    assertThat(all.volatility()).isEqualByComparingTo(expected.volatility());
    assertThat(row(LeaderboardPeriod.ALL, "alice").sharpe()).isEqualByComparingTo(expected.sharpe());
    assertThat(row(LeaderboardPeriod.WEEK, "alice").sharpe()).as("a week never has 20 returns").isNull();
    assertThat(lk.stats.myStats(alice.id(), LeaderboardPeriod.TODAY).dailyReturns()).isEqualTo(0);
  }
}
