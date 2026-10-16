package com.stocks.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.TransferType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.portfolio.AccountPnl;
import com.stocks.contract.portfolio.LeaderboardService;
import com.stocks.contract.portfolio.PositionDayPnl;
import com.stocks.contract.portfolio.dto.LeaderboardDto;
import com.stocks.contract.portfolio.dto.LeaderboardDto.ArenaDto;
import com.stocks.contract.portfolio.dto.LeaderboardDto.BenchmarkReturnDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto.HistoryPointDto;
import com.stocks.contract.portfolio.dto.PublicHistoryDto;
import com.stocks.contract.portfolio.dto.PublicTradeDto;
import com.stocks.testkit.Fixtures.AccountRef;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Account-page performance (§16.2/§16.3: day and total P&amp;L, position day P&amp;L with a rename), equity curves, and
 * the public profile data (§16.7: delayed trades, rationale visibility, EOD weights, hidden equity).
 */
class AccountPerformanceTest {

  static final Instant MON_10 = Instant.parse("2026-10-05T14:00:00Z");
  static final LocalDate MON = LocalDate.of(2026, 10, 5);
  static final LocalDate TUE = LocalDate.of(2026, 10, 6);
  static final Instant TUE_11 = Instant.parse("2026-10-06T15:00:00Z");
  static final Instant WED_10 = Instant.parse("2026-10-07T14:00:00Z");

  PortfolioKit kit;
  PlayerRef alice;
  AccountRef acc;

  @AfterEach
  void closeDb() {
    kit.close();
  }

  @BeforeEach
  void setUp() {
    kit = new PortfolioKit();
    kit.at(MON_10);
    alice = kit.data.human("alice");
    acc = kit.data.open(alice, "500.00");
    aliceId = alice.publicId();
  }

  /** golden twr_deposit through the real services: Mon open, Mon EOD 550, Tue 11:00 deposit at 560, Tue EOD 1100. */
  private void twrDepositScenario() {
    kit.data.buy(acc, "SIMA", "5", "100.00");
    kit.market.dayEndMark("SIMA", MON, "110.00");
    kit.at(Instant.parse("2026-10-06T00:20:00Z"));
    kit.eodJob().runFor(MON);
    kit.market.print("SIMA", Instant.parse("2026-10-06T14:59:00Z"), "112.00", 10);
    kit.at(TUE_11);
    kit.data.deposit(acc, "500.00");
    kit.market.dayEndMark("SIMA", TUE, "120.00");
    kit.market.print("SIMA", Instant.parse("2026-10-06T19:59:00Z"), "120.00", 10);
    kit.at(Instant.parse("2026-10-07T00:20:00Z"));
    kit.eodJob().runFor(TUE);
  }

  @Test
  void dayAndTotalPnlAreRunRelativeAndDepositAware() {
    twrDepositScenario();
    // Tue 15:00 ET: the TODAY anchor is Monday's EOD (550, U 1.10); the $500 deposit is a flow, not P&L
    kit.market.print("SIMA", Instant.parse("2026-10-06T18:59:00Z"), "118.00", 10);
    kit.at(Instant.parse("2026-10-06T19:00:00Z"));
    AccountPnl tue = kit.accounts.accountPnl(acc.id());
    assertThat(tue.dayPnl()).isEqualByComparingTo("40.00");        // 1090 − 550 − 500
    assertThat(tue.totalPnl()).isEqualByComparingTo("90.00");      // 1090 − 1000
    assertThat(tue.unitValue().setScale(10, java.math.RoundingMode.HALF_EVEN))
        .isEqualByComparingTo("1.1516981132");
    assertThat(tue.totalReturn()).isEqualByComparingTo("0.1516981132");
    assertThat(tue.dayReturn()).isEqualByComparingTo("0.0469982847");

    // Wed 10:00 ET at 120: TODAY anchor = Tuesday's EOD (1100); since open +16.23%, +$100
    kit.market.print("SIMA", Instant.parse("2026-10-07T13:59:00Z"), "120.00", 10);
    kit.at(WED_10);
    AccountPnl wed = kit.accounts.accountPnl(acc.id());
    assertThat(wed.dayPnl()).isEqualByComparingTo("0.00");
    assertThat(wed.dayReturn()).isEqualByComparingTo("0");
    assertThat(wed.totalPnl()).isEqualByComparingTo("100.00");
    assertThat(wed.totalReturn()).isEqualByComparingTo("0.1622641509");
    assertThat(wed.asOf()).isEqualTo(WED_10);
  }

  @Test
  void aRunOpenedTodayAnchorsAtItsOpening() {
    kit.data.buy(acc, "SIMA", "1", "100.00");
    kit.market.print("SIMA", MON_10.plusSeconds(30), "105.00", 10);
    kit.at(MON_10.plusSeconds(60));
    AccountPnl pnl = kit.accounts.accountPnl(acc.id());
    assertThat(pnl.dayPnl()).isEqualByComparingTo("5.00");
    assertThat(pnl.dayReturn()).isEqualByComparingTo("0.01");
    assertThat(pnl.totalPnl()).isEqualByComparingTo("5.00");
    Map<String, PositionDayPnl> pos = kit.accounts.positionDayPnl(acc.id());
    assertThat(pos.get("SIMA").dayPnl()).isEqualByComparingTo("5.00");   // 105 − 0 + (−100)
    assertThat(pos.get("SIMA").dayReturn()).isNull();                   // no previous-EOD market value
  }

  @Test
  void positionDayPnlUsesThePreviousEodUnderTheOldSymbolAfterARename() {
    kit.data.buy(acc, "OLD", "10", "20.00");
    kit.market.dayEndMark("OLD", MON, "21.00");
    kit.at(Instant.parse("2026-10-06T00:20:00Z"));
    kit.eodJob().runFor(MON);                                       // Mon EOD: OLD 10 × 21 = 210
    kit.at(Instant.parse("2026-10-06T07:45:00Z"));                  // Tue 03:45 ET: the name change
    kit.data.rename(acc, "OLD", "NEW");
    kit.at(Instant.parse("2026-10-06T14:00:00Z"));
    kit.data.buy(acc, "NEW", "5", "22.00");                         // +5 at 22 today
    kit.market.print("NEW", Instant.parse("2026-10-06T14:59:00Z"), "23.00", 10);
    kit.at(TUE_11);
    Map<String, PositionDayPnl> pos = kit.accounts.positionDayPnl(acc.id());
    assertThat(pos).containsOnlyKeys("NEW");
    // 15 × 23 − 210 − 110 = 25 (not 345 − 0 − 110 = 235, which a lookup under the new symbol would show)
    assertThat(pos.get("NEW").dayPnl()).isEqualByComparingTo("25.00");
    assertThat(pos.get("NEW").dayReturn()).isEqualByComparingTo("0.1190476190");
    assertThat(kit.accounts.positionDayPnl(kit.data.open(kit.data.human("empty"), "10.00").id())).isEmpty();
  }

  @Test
  void aClosedRunReportsItsFinalValues() {
    kit.data.buy(acc, "SIMA", "5", "100.00");
    kit.market.print("SIMA", MON_10.plusSeconds(30), "60.00", 10);
    kit.at(MON_10.plusSeconds(60));
    kit.data.sell(acc, "SIMA", "5", "60.00");
    AccountRef run2 = kit.data.reset(acc, "500.00");
    AccountPnl closed = kit.accounts.accountPnl(acc.id());
    assertThat(closed.totalPnl()).isEqualByComparingTo("-200.00");
    assertThat(closed.totalReturn()).isEqualByComparingTo("-0.4");
    assertThat(closed.unitValue()).isEqualByComparingTo("0.6");
    assertThat(closed.dayPnl()).isEqualByComparingTo("0");
    AccountPnl open = kit.accounts.accountPnl(run2.id());
    assertThat(open.totalPnl()).isEqualByComparingTo("0.00");
    assertThat(open.totalReturn()).isEqualByComparingTo("0");     // run-relative on the account page
    assertThat(open.unitValue()).isEqualByComparingTo("0.6");
    assertThatThrownBy(() -> kit.accounts.accountPnl(424242L))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
  }

  // ------------------------------------------------------------------------------------------- history

  @Test
  void theAccountHistoryRunsFromTheAnchorThroughEodPointsToALivePoint() {
    twrDepositScenario();
    kit.market.print("SIMA", Instant.parse("2026-10-07T13:59:00Z"), "120.00", 10);
    kit.at(WED_10);
    PortfolioHistoryDto all = kit.history.history(acc.id(), HistoryRange.ALL);
    assertThat(all.range()).isEqualTo(HistoryRange.ALL);
    List<HistoryPointDto> p = all.points();
    assertThat(p).extracting(HistoryPointDto::t).containsExactly(MON_10, Instant.parse("2026-10-06T00:00:00Z"),
        Instant.parse("2026-10-07T00:00:00Z"), WED_10);
    assertThat(p).extracting(HistoryPointDto::equity).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactly(new BigDecimal("500.00"), new BigDecimal("550.00"), new BigDecimal("1100.00"),
            new BigDecimal("1100.00"));
    assertThat(p.get(1).pnl()).isEqualByComparingTo("50.00");
    assertThat(p.get(3).pnl()).isEqualByComparingTo("100.00");
    assertThat(p.get(3).netContributions()).isEqualByComparingTo("1000.00");
    assertThat(all.flows()).singleElement().satisfies(f -> {
      assertThat(f.type()).isEqualTo(TransferType.DEPOSIT);
      assertThat(f.amount()).isEqualByComparingTo("500.00");
      assertThat(f.t()).isEqualTo(TUE_11);
    });
    assertThat(all.summary().ret()).isEqualByComparingTo("0.1622641509");
    assertThat(all.summary().pnl()).isEqualByComparingTo("100.00");
    assertThat(all.summary().maxDrawdown()).isEqualByComparingTo("0");
    assertThat(all.summary().excessVsBenchmark()).isNull();         // no SPY data in this test

    PortfolioHistoryDto day = kit.history.history(acc.id(), HistoryRange.D1);
    assertThat(day.points()).hasSize(2);                           // Tuesday's EOD, then live (no samples taken)
    assertThat(day.summary().pnl()).isEqualByComparingTo("0.00");
    assertThat(day.flows()).isEmpty();
    kit.sampler.sample(WED_10.minusSeconds(300));
    assertThat(kit.history.history(acc.id(), HistoryRange.D1).points()).hasSize(3);
    PortfolioHistoryDto week = kit.history.history(acc.id(), null);   // default 1M: opened later → its opening
    assertThat(week.range()).isEqualTo(HistoryRange.M1);
    assertThat(week.points().get(0).t()).isEqualTo(MON_10);
  }

  @Test
  void thePublicHistoryChainsUnitValuesAcrossRunsWithEquityAlways() {
    twrDepositScenario();
    kit.at(WED_10);
    PlayerDirectory.PlayerRow row = kit.players.byHandle("alice").orElseThrow();
    PublicHistoryDto h = kit.history.publicHistory(row, HistoryRange.ALL);
    assertThat(h.points()).hasSize(4);
    assertThat(h.points().get(0).unitValue()).isEqualByComparingTo("1");
    assertThat(h.points().get(2).unitValue().setScale(10, java.math.RoundingMode.HALF_EVEN))
        .isEqualByComparingTo("1.1622641509");
    assertThat(h.points()).allSatisfy(pt -> assertThat(pt.equity()).isNotNull());
    PublicHistoryDto week = kit.history.publicHistory(row, HistoryRange.W1);   // public mode (§16.7): equity always
    assertThat(week.points()).isNotEmpty().allSatisfy(pt -> assertThat(pt.equity()).isNotNull());
    PublicHistoryDto none = kit.history.publicHistory(kit.players.byId(kit.data.human("nobody").id())
        .orElseThrow(), HistoryRange.D1);
    assertThat(none.points()).isEmpty();
  }

  // ------------------------------------------------------------------------------------------- public profile

  @Test
  void publicTradesAreLiveByDefault() {
    kit.data.trade(acc, "BUY", "SIMA", "1", "100.00", "WEB", "my public plan", true);
    PublicProfileService profiles = kit.profiles(stubBoard());
    assertThat(profiles.publicTrades("alice", null, 50).items()).singleElement().satisfies(t -> {
      assertThat(t.symbol()).isEqualTo("SIMA");
      assertThat(t.viaApi()).isFalse();
      assertThat(t.untrusted().rationale()).isEqualTo("my public plan");
    });
  }

  @Test
  void publicTradesFollowTheFeedDelayLabelAndShowOnlyPublicRationales() {
    PlayerRef bot = kit.data.ai("claude", alice);
    AccountRef botAcc = kit.data.open(bot, "500.00");
    kit.data.trade(botAcc, "BUY", "SIMA", "1", "100.00", "MCP_KEY", "Momentum " + "x".repeat(400), true);
    kit.at(MON_10.plusSeconds(60));
    kit.data.trade(botAcc, "BUY", "SIMB", "1", "50.00", "REST_KEY", "private", false);
    kit.at(MON_10.plusSeconds(120));
    kit.data.trade(acc, "BUY", "SIMA", "1", "100.00", "WEB", "my secret plan", false);
    PortfolioKit delayed = new PortfolioKit(kit.db, kit.time, NotImplementedStub.of(MarketCalendar.class, "WS2"),
        PortfolioKit.feedDelay(Duration.ofMinutes(15)));
    PublicProfileService profiles = delayed.profiles(stubBoard());
    kit.at(MON_10.plusSeconds(14 * 60));                            // 14 min: nothing is visible yet
    assertThat(profiles.publicTrades("claude", null, 50).items()).isEmpty();
    kit.at(MON_10.plusSeconds(16 * 60));                            // 16 min: the first two
    Page<PublicTradeDto> page = profiles.publicTrades("CLAUDE", null, 1);
    assertThat(page.items()).singleElement().satisfies(t -> {
      assertThat(t.symbol()).isEqualTo("SIMB");
      assertThat(t.viaApi()).isTrue();
      assertThat(t.untrusted().rationale()).isNull();               // not public
    });
    Page<PublicTradeDto> next = profiles.publicTrades("claude", Cursor.parse(page.nextCursor()), 1);
    assertThat(next.items()).singleElement().satisfies(t -> {
      assertThat(t.symbol()).isEqualTo("SIMA");
      assertThat(t.untrusted().rationale()).hasSize(PublicProfileService.RATIONALE_MAX).startsWith("Momentum");
    });
    assertThat(next.nextCursor()).isNull();
    assertThat(profiles.publicTrades("alice", null, 50).items()).isEmpty();   // 14 min old
    kit.at(MON_10.plusSeconds(20 * 60));
    assertThat(profiles.publicTrades("alice", null, 50).items()).singleElement()
        .satisfies(t -> assertThat(t.untrusted().rationale()).isNull());
    assertThatThrownBy(() -> profiles.publicTrades("nobody", null, 10))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
    kit.data.fx.status(bot, "DISABLED");
    assertThatThrownBy(() -> profiles.player("claude")).isInstanceOf(ApiException.class);   // hidden from the league
  }

  @Test
  void theProfileShowsLiveEquityEodHoldingsWithQuantitiesAndDeposits() {
    twrDepositScenario();
    kit.at(WED_10);
    PublicProfileService profiles = kit.profiles(stubBoard());
    PlayerProfileDto p = profiles.profile("alice", null);
    assertThat(p.player().handle()).isEqualTo("alice");
    assertThat(p.player().resets()).isZero();
    assertThat(p.ownerHandle()).isNull();
    assertThat(p.runNo()).isEqualTo(1);
    assertThat(p.equity()).isEqualByComparingTo("1100.00");
    assertThat(p.netContributions()).isEqualByComparingTo("1000.00");
    assertThat(p.deposits().count()).isEqualTo(1);
    assertThat(p.deposits().total()).isEqualByComparingTo("500.00");
    assertThat(p.fills()).isEqualTo(1);
    assertThat(p.winRate()).isNull();
    assertThat(p.moneyWeightedReturnAll()).isNotNull();
    assertThat(p.holdingsAsOf()).isEqualTo(TUE);
    assertThat(p.holdings()).singleElement().satisfies(h -> {
      assertThat(h.symbol()).isEqualTo("SIMA");
      assertThat(h.weight()).isEqualByComparingTo("0.5455");          // 600 / 1100
      assertThat(h.qty()).isEqualByComparingTo("5");                 // public mode (§16.7): quantities too
    });
    assertThat(p.stats().week().ret()).isEqualByComparingTo("0.25");
    assertThat(p.stats().today().rank()).isEqualTo(1);
    assertThat(p.recentTrades()).hasSize(1);
  }

  @Test
  void anAiProfileNamesItsOwner() {
    PlayerRef bot = kit.data.ai("claude", alice);
    kit.data.open(bot, "500.00");
    PlayerProfileDto p = kit.profiles(stubBoard()).profile("claude", null);
    assertThat(p.ownerHandle()).isEqualTo("alice");
    assertThat(p.player().aiModel()).isEqualTo("Claude");
    assertThat(p.stats().all().rank()).isNull();                    // not on the stub board
    assertThat(p.holdings()).isEmpty();
  }

  /** A leaderboard that ranks alice first with a fixed 25% in every period. */
  static LeaderboardService stubBoard() {
    return (period, metric, kind, limit, viewer) -> new LeaderboardDto(period, LeaderboardMetric.RETURN,
        KindFilter.ALL, TestTimes.START, TestTimes.START, new BenchmarkReturnDto("SPY", null),
        new ArenaDto(null, null), List.of(new com.stocks.contract.portfolio.dto.LeaderboardRowDto(1, null, true, null,
            false, false, new com.stocks.contract.identity.dto.PlayerSummaryDto(aliceId, "alice", "alice", null, null,
                null, null, 0), false, new BigDecimal("0.25"), new BigDecimal("10.00"), null, null, null,
            new BigDecimal("500.00"), BigDecimal.ZERO, 0, 0, null, null, List.of(), null, null, null, null, null,
            null)), null, null);
  }

  static String aliceId;
}
