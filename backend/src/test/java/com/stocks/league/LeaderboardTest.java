package com.stocks.league;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.contract.portfolio.dto.FeedItemDto.FeedItemKind;
import com.stocks.contract.portfolio.dto.LeaderboardDto;
import com.stocks.contract.portfolio.dto.LeaderboardRowDto;
import com.stocks.contract.realtime.dto.LeaderboardUpdateDto;
import com.stocks.league.LeagueRepository.SnapshotRow;
import com.stocks.portfolio.PortfolioData;
import com.stocks.portfolio.PortfolioKit;
import com.stocks.testkit.Fixtures.AccountRef;
import com.stocks.testkit.Fixtures.PlayerRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

/**
 * The deposit-aware leaderboard (§16.5) end to end on a fresh database: the §16.2 golden scenarios through real
 * valuations, anchors and the board (a deposit never moves TWR, a $0.40 account, a rebase, a reset that does not
 * erase a loss), eligibility, ties, kinds, the EOD {@code leaderboard_snapshot} with rank arrows and NEW_LEADER.
 */
class LeaderboardTest {

  static final Instant MON_10 = Instant.parse("2026-10-05T14:00:00Z");
  static final Instant WED_11 = Instant.parse("2026-10-07T15:00:00Z");
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

  LeaderboardDto board(LeaderboardPeriod period) {
    return board(period, LeaderboardMetric.RETURN, KindFilter.ALL, null);
  }

  LeaderboardDto board(LeaderboardPeriod period, LeaderboardMetric metric, KindFilter kind, Long viewer) {
    lk.leaderboard.refresh();
    return lk.leaderboard.board(period, metric, kind, 50, viewer);
  }

  static LeaderboardRowDto row(LeaderboardDto board, String handle) {
    return board.rows().stream().filter(r -> r.player().handle().equals(handle)).findFirst().orElseThrow();
  }

  // ------------------------------------------------------------------------------------------- deposits

  @Test
  void aDepositToAllMovesNoReturnAndShowsUpAsAPublicFlow() {      // golden twr_deposit_all
    PlayerRef alice = data.human("alice");
    PlayerRef bob = data.human("bob");
    AccountRef a = data.open(alice, "500.00");
    AccountRef b = data.open(bob, "500.00");
    data.buy(a, "SIMA", "5", "100.00");
    data.buy(b, "SIMB", "5", "100.00");
    kit.market.print("SIMA", WED_11.minusSeconds(60), "120.00", 10).print("SIMB", WED_11.minusSeconds(60), "90.00", 10);
    kit.at(WED_11);

    LeaderboardDto today = board(LeaderboardPeriod.TODAY);
    assertThat(today.rows()).extracting(r -> r.player().handle()).containsExactly("alice", "bob");
    assertThat(row(today, "alice").rank()).isEqualTo(1);
    assertThat(row(today, "alice").ret()).isEqualByComparingTo("0.2");
    assertThat(row(today, "alice").pnl()).isEqualByComparingTo("100.00");
    assertThat(row(today, "bob").ret()).isEqualByComparingTo("-0.1");
    assertThat(row(today, "bob").pnl()).isEqualByComparingTo("-50.00");

    data.deposit(a, "1000.00");
    data.deposit(b, "1000.00");
    LeaderboardDto after = board(LeaderboardPeriod.TODAY);
    assertThat(after.rows()).extracting(r -> r.player().handle()).containsExactly("alice", "bob");
    LeaderboardRowDto alice2 = row(after, "alice");
    assertThat(alice2.ret()).isEqualByComparingTo("0.2");          // unchanged
    assertThat(alice2.pnl()).isEqualByComparingTo("100.00");       // unchanged
    assertThat(alice2.equity()).isEqualByComparingTo("1600.00");
    assertThat(alice2.netContributions()).isEqualByComparingTo("1500.00");
    assertThat(alice2.flowsInPeriod()).isEqualByComparingTo("1000.00");   // deposits are public
    assertThat(row(after, "bob").ret()).isEqualByComparingTo("-0.1");
    assertThat(row(after, "bob").pnl()).isEqualByComparingTo("-50.00");

    LeaderboardDto all = board(LeaderboardPeriod.ALL);
    assertThat(row(all, "alice").ret()).isEqualByComparingTo("0.2");
    assertThat(row(all, "alice").eligible()).isTrue();              // 1 fill, 1 symbol: ALL has no minimum
    assertThat(row(all, "alice").rank()).isNotNull();
    assertThat(row(all, "alice").eligibilityNote()).isNull();
    assertThat(row(all, "alice").flowsInPeriod()).isEqualByComparingTo("1000.00");   // the INITIAL is the anchor
    assertThat(all.periodStart()).isEqualTo(MON_10);
    assertThat(all.arena().humansMedianReturn()).isEqualByComparingTo("0.05");
    assertThat(all.arena().aiMedianReturn()).isNull();
    assertThat(all.benchmark().symbol()).isEqualTo("SPY");
    assertThat(all.benchmark().ret()).isNull();                     // no SPY data
    LeaderboardDto me = board(LeaderboardPeriod.TODAY, LeaderboardMetric.RETURN, KindFilter.ALL, bob.id());
    assertThat(me.me()).isNotNull();
    assertThat(me.me().player().handle()).isEqualTo("bob");
    assertThat(me.me().rank()).isEqualTo(2);
  }

  @Test
  void anAccountBelowOneDollarStillReceivesTheDepositAndKeepsItsReturn() {   // golden twr_tiny_equity
    PlayerRef owner = data.human("owner");
    data.open(owner, "500.00");
    PlayerRef tiny = data.ai("tiny", owner);
    AccountRef t = data.open(tiny, "500.00");
    data.buy(t, "SIMT", "5", "100.00");
    PlayerRef zero = data.ai("zero", owner);
    AccountRef z = data.open(zero, "500.00");
    data.pnlCorrection(z, "-500.00");                             // wiped out by "performance": E = 0, 500 units
    kit.market.print("SIMT", WED_11.minusSeconds(60), "0.08", 10);
    kit.at(WED_11);
    assertThat(row(board(LeaderboardPeriod.ALL), "tiny").ret()).isEqualByComparingTo("-0.9992");

    var flow = data.deposit(t, "500.00");
    assertThat(flow.unitsDelta()).isEqualByComparingTo("625000");
    assertThat(flow.rebased()).isFalse();
    LeaderboardDto all = board(LeaderboardPeriod.ALL);
    LeaderboardRowDto tinyRow = row(all, "tiny");
    assertThat(tinyRow.ret()).isEqualByComparingTo("-0.9992");      // unchanged by the deposit
    assertThat(tinyRow.equity()).isEqualByComparingTo("500.40");
    assertThat(tinyRow.rebased()).isFalse();

    var rebase = data.deposit(z, "500.00");
    assertThat(rebase.rebased()).isTrue();
    assertThat(rebase.unitSeriesAfter()).isEqualTo(2);
    LeaderboardRowDto zeroRow = row(board(LeaderboardPeriod.ALL), "zero");
    assertThat(zeroRow.ret()).isEqualByComparingTo("-1");          // a window spanning the rebase: −100%
    assertThat(zeroRow.rebased()).isTrue();
    assertThat(zeroRow.maxDrawdown()).isEqualByComparingTo("1");
    assertThat(zeroRow.pnl()).isEqualByComparingTo("-500.00");
    LeaderboardDto ai = board(LeaderboardPeriod.ALL, LeaderboardMetric.RETURN, KindFilter.AI, null);
    assertThat(ai.rows()).extracting(r -> r.player().handle()).containsExactlyInAnyOrder("tiny", "zero");
    assertThat(ai.arena().aiMedianReturn()).isNotNull();
  }

  // ------------------------------------------------------------------------------------------- resets

  @Test
  void aResetNeverErasesALoss() {                                  // golden twr_reset
    kit.at(Instant.parse("2026-10-01T14:00:00Z"));                 // Thu Oct 1
    PlayerRef p = data.human("phoenix");
    AccountRef run1 = data.open(p, "500.00");
    data.buy(run1, "SIMR", "5", "100.00");
    kit.market.dayEndMark("SIMR", LocalDate.of(2026, 10, 2), "100.00");   // WEEK anchor: Fri Oct 2 EOD, E = 500
    kit.at(Instant.parse("2026-10-08T15:00:00Z"));                 // Thu Oct 8 11:00 ET
    data.sell(run1, "SIMR", "5", "60.00");                         // E = 300 → U = 0.60
    AccountRef run2 = data.reset(run1, "500.00");
    data.buy(run2, "SIMQ", "5", "100.00");
    kit.market.dayEndMark("SIMQ", LocalDate.of(2026, 10, 9), "102.00");   // Fri EOD: E = 510 → U = 0.612
    kit.market.print("SIMQ", Instant.parse("2026-10-09T19:59:00Z"), "102.00", 10);
    kit.at(Instant.parse("2026-10-10T00:30:00Z"));                 // Fri 20:30 ET

    LeaderboardRowDto week = row(board(LeaderboardPeriod.WEEK), "phoenix");
    assertThat(week.ret()).isEqualByComparingTo("-0.388");          // a per-run anchor would show +2.0%
    assertThat(week.pnl()).isEqualByComparingTo("-190.00");          // (300 − 500) + (510 − 500)
    assertThat(week.partialPeriod()).isFalse();
    assertThat(week.player().resets()).isEqualTo(1);
    assertThat(week.flowsInPeriod()).isEqualByComparingTo("500.00");   // the new run's INITIAL
    assertThat(week.fills()).isEqualTo(2);                          // the sell's order and the new buy
    assertThat(week.symbolsTraded()).isEqualTo(2);
    assertThat(week.winRate()).isEqualByComparingTo("0");           // one closing sell, at a loss
    LeaderboardRowDto all = row(board(LeaderboardPeriod.ALL), "phoenix");
    assertThat(all.ret()).isEqualByComparingTo("-0.388");
    assertThat(all.pnl()).isEqualByComparingTo("-190.00");

    // the same numbers as of Friday's cut-off, from the EOD snapshots (leaderboard_snapshot)
    kit.eodJob().runFor(LocalDate.of(2026, 10, 9));
    var eod = lk.computer.endOfDay(LocalDate.of(2026, 10, 9)).periods().get(LeaderboardPeriod.WEEK).entries();
    assertThat(eod).singleElement().satisfies(e -> {
      assertThat(e.twr()).isEqualByComparingTo("-0.388");
      assertThat(e.pnl()).isEqualByComparingTo("-190.00");
      assertThat(e.accountId()).isEqualTo(run2.id());
    });
  }

  // ------------------------------------------------------------------------------------------- eligibility, kinds

  @Test
  void eligibilityTiesAndKinds() {
    PlayerRef h1 = data.human("hana");
    AccountRef a1 = data.open(h1, "1000.00");
    kit.at(MON_10.plusSeconds(1));
    PlayerRef bot = data.ai("bot", h1);
    AccountRef a2 = data.open(bot, "1000.00");
    PlayerRef h2 = data.human("hugo");
    AccountRef a3 = data.open(h2, "1000.00");
    for (int i = 0; i < 5; i++) {
      String sym = i % 2 == 0 ? "SIMA" : "SIMB";
      data.trade(a1, "BUY", sym, "1", "100.00", "WEB", null, false);
      data.trade(a2, "BUY", sym, "1", "100.00", "MCP_KEY", "why", true);
    }
    data.trade(a3, "BUY", "SIMA", "1", "100.00", "REST_KEY", null, false);
    kit.at(WED_11);
    LeaderboardDto week = board(LeaderboardPeriod.WEEK);
    // equal returns (0%) and P&L: the earlier first opening wins
    assertThat(week.rows()).extracting(LeaderboardRowDto::rank).containsExactly(1, 2, null);
    assertThat(week.rows()).extracting(r -> r.player().handle()).containsExactly("hana", "bot", "hugo");
    assertThat(row(week, "hana").partialPeriod()).isTrue();          // joined after the WEEK anchor
    assertThat(row(week, "hana").fills()).isEqualTo(5);
    assertThat(row(week, "hana").symbolsTraded()).isEqualTo(2);
    assertThat(row(week, "hugo").eligibilityNote()).isEqualTo("1/5 trades · 1/2 symbols");
    assertThat(row(week, "hugo").apiTrader()).isTrue();             // a human with an API-key fill
    assertThat(row(week, "hana").apiTrader()).isFalse();
    assertThat(row(week, "bot").apiTrader()).isFalse();             // bots are AI, not "API traders"
    assertThat(row(week, "bot").player().kind()).isEqualTo(com.stocks.common.domain.PlayerKind.AI);
    assertThat(row(week, "bot").spark()).isNotEmpty();
    assertThat(board(LeaderboardPeriod.WEEK, LeaderboardMetric.RETURN, KindFilter.HUMAN, null).rows())
        .extracting(r -> r.player().handle()).containsExactly("hana", "hugo");
    assertThat(board(LeaderboardPeriod.WEEK, LeaderboardMetric.PNL, KindFilter.AI, null).rows())
        .extracting(LeaderboardRowDto::rank).containsExactly(1);
    assertThat(lk.leaderboard.board(LeaderboardPeriod.WEEK, LeaderboardMetric.RETURN, KindFilter.ALL, 2, null).rows())
        .hasSize(2);
    // a disabled player disappears from the league
    data.fx.status(h2, "DISABLED");
    assertThat(board(LeaderboardPeriod.WEEK).rows()).extracting(r -> r.player().handle()).doesNotContain("hugo");
  }

  // ------------------------------------------------------------------------------------------- EOD snapshot

  @Test
  void theEodSnapshotDrivesRankArrowsAndNewLeaderEvents() {
    PlayerRef alice = data.human("alice");
    PlayerRef bob = data.human("bob");
    AccountRef a = data.open(alice, "500.00");
    AccountRef b = data.open(bob, "500.00");
    for (int i = 0; i < 5; i++) {                                   // 5 fills over 2 symbols each: eligible
      data.buy(a, i % 2 == 0 ? "SIMA" : "SIMC", "1", "100.00");
      data.buy(b, i % 2 == 0 ? "SIMB" : "SIMD", "1", "100.00");
    }
    marks(MON, "110.00", "105.00");                                 // alice 550 (+10%), bob 525 (+5%)
    kit.at(Instant.parse("2026-10-06T00:20:00Z"));
    kit.eodJob().runFor(MON);
    assertThat(lk.snapshotWriter.write(MON)).isEqualTo(12);          // 6 periods × 2 players
    List<SnapshotRow> today = lk.snapshotWriter.rows(LeaderboardPeriod.TODAY, MON);
    assertThat(today).extracting(SnapshotRow::playerId).containsExactly(alice.id(), bob.id());
    assertThat(today.get(0).rank()).isEqualTo(1);
    assertThat(today.get(0).twr()).isEqualByComparingTo("0.1");
    assertThat(today.get(0).equity()).isEqualByComparingTo("550.00");
    assertThat(today.get(0).fills()).isEqualTo(5);
    assertThat(lk.snapshotWriter.rows(LeaderboardPeriod.ALL, MON)).extracting(SnapshotRow::rank).containsExactly(1, 2);
    lk.snapshotWriter.write(MON);                                   // idempotent
    assertThat(kit.db.jdbc().sql("SELECT COUNT(*) FROM leaderboard_snapshot").query(Long.class).single())
        .isEqualTo(12);
    // the feed: every fill and both joins backfilled, alice is the new WEEK leader
    assertThat(kinds()).filteredOn(k -> k == FeedItemKind.FILL).hasSize(10);
    assertThat(kinds()).filteredOn(k -> k == FeedItemKind.JOINED).hasSize(2);
    assertThat(kinds()).filteredOn(k -> k == FeedItemKind.NEW_LEADER).hasSize(1);

    marks(TUE, "100.00", "120.00");                                 // alice 500, bob 600
    kit.at(Instant.parse("2026-10-07T00:20:00Z"));
    kit.eodJob().runFor(TUE);
    lk.snapshotWriter.write(TUE);
    assertThat(lk.snapshotWriter.rows(LeaderboardPeriod.WEEK, TUE).get(0).playerId()).isEqualTo(bob.id());
    assertThat(kinds()).filteredOn(k -> k == FeedItemKind.NEW_LEADER).hasSize(2);

    // Wednesday live: alice back on top of TODAY, one place up; bob one place down
    for (String s : List.of("SIMA", "SIMC")) {
      kit.market.print(s, WED_11.minusSeconds(60), "130.00", 10);
    }
    for (String s : List.of("SIMB", "SIMD")) {
      kit.market.print(s, WED_11.minusSeconds(60), "100.00", 10);
    }
    kit.at(WED_11);
    LeaderboardDto live = board(LeaderboardPeriod.TODAY);
    assertThat(row(live, "alice").rank()).isEqualTo(1);
    assertThat(row(live, "alice").rankChange()).isEqualTo(1);
    assertThat(row(live, "bob").rankChange()).isEqualTo(-1);
    assertThat(row(live, "alice").ret()).isEqualByComparingTo("0.3");
    assertThat(row(live, "alice").topHolding()).isEqualTo("SIMA");    // previous-EOD weights
    assertThat(board(LeaderboardPeriod.TODAY, LeaderboardMetric.PNL, KindFilter.ALL, null).rows().get(0)
        .rankChange()).isNull();                                    // arrows only for RETURN / ALL
    LeaderboardRowDto week = row(board(LeaderboardPeriod.WEEK), "bob");
    assertThat(week.spark()).hasSize(3);                             // Mon, Tue EOD + live
    assertThat(week.maxDrawdown()).isEqualByComparingTo(d("1").subtract(d("100").divide(d("120"), 10,
        RoundingMode.HALF_EVEN)));
  }

  private void marks(LocalDate day, String alice, String bob) {
    kit.market.dayEndMark("SIMA", day, alice).dayEndMark("SIMC", day, alice).dayEndMark("SIMB", day, bob)
        .dayEndMark("SIMD", day, bob);
  }

  private List<FeedItemKind> kinds() {
    return kit.db.jdbc().sql("SELECT event_kind FROM feed_event").query(String.class).list().stream()
        .map(FeedItemKind::valueOf).toList();
  }

  // ------------------------------------------------------------------------------------------- live push

  @Test
  void theRecomputePushesAsOfOnlyWhenSomethingChanged() {
    SimpMessageSendingOperations ops = mock(SimpMessageSendingOperations.class);
    lk.beans.addBean("brokerMessagingTemplate", ops);
    PlayerRef alice = data.human("alice");
    AccountRef a = data.open(alice, "500.00");
    data.buy(a, "SIMA", "5", "100.00");
    kit.at(WED_11);
    assertThat(lk.leaderboard.name()).isEqualTo("LEADERBOARD_RECOMPUTE");
    assertThat(lk.leaderboard.interval()).hasSeconds(5);
    assertThat(lk.leaderboard.sessionsOnly()).isFalse();
    lk.leaderboard.tick();
    lk.leaderboard.tick();                                          // nothing changed: no second push
    verify(ops, times(1)).convertAndSend(eq(LeaderboardServiceImpl.TOPIC), any(LeaderboardUpdateDto.class));
    kit.market.print("SIMA", WED_11.minusSeconds(1), "101.00", 10);
    lk.leaderboard.tick();
    verify(ops, times(2)).convertAndSend(eq(LeaderboardServiceImpl.TOPIC), any(LeaderboardUpdateDto.class));
    LeaderboardDto defaults = lk.leaderboard.board(null, null, null, 0, null);
    assertThat(defaults.period()).isEqualTo(LeaderboardPeriod.WEEK);
    assertThat(defaults.metric()).isEqualTo(LeaderboardMetric.RETURN);
    assertThat(defaults.kind()).isEqualTo(KindFilter.ALL);
    assertThat(defaults.asOf()).isEqualTo(WED_11);
  }
}
