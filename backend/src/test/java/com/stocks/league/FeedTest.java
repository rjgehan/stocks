package com.stocks.league;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TransferType;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.events.CashTransferPosted;
import com.stocks.common.events.ExecutionCreated;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.portfolio.dto.FeedItemDto;
import com.stocks.contract.portfolio.dto.FeedItemDto.FeedItemKind;
import com.stocks.portfolio.PortfolioData;
import com.stocks.portfolio.PortfolioKit;
import com.stocks.testkit.Fixtures.AccountRef;
import com.stocks.testkit.Fixtures.PlayerRef;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

/**
 * The league feed (§12.8): {@link FeedEventListener} is the only writer, idempotent by {@code source_ref}; trades
 * appear at once (public mode, §16.7) or after the admin's delay, with public rationales only; deposits are public; the
 * publisher pushes each row once when it becomes visible, also when its insert commits after its {@code visible_at}.
 */
class FeedTest {

  static final Instant MON_10 = Instant.parse("2026-10-05T14:00:00Z");

  LeagueKit lk;
  PortfolioKit kit;
  PortfolioData data;
  PlayerRef alice;
  AccountRef acc;

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
    alice = data.human("alice");
    acc = data.open(alice, "500.00");
  }

  List<FeedItemDto> visible() {
    return lk.feedService.page(null, 50).items();
  }

  /** The same league on the same database with {@code social.tradeFeedDelay = PT15M}. */
  LeagueKit delayed() {
    return new LeagueKit(new PortfolioKit(kit.db, kit.time, NotImplementedStub.of(MarketCalendar.class, "WS2"),
        PortfolioKit.feedDelay(Duration.ofMinutes(15))));
  }

  String visibleAt(String sourceRef) {
    return kit.db.jdbc().sql("SELECT visible_at FROM feed_event WHERE source_ref = ?").param(sourceRef)
        .query((rs, n) -> com.stocks.common.jdbc.Jdbc.instant(rs, "visible_at").toString()).single();
  }

  @Test
  void byDefaultAFillIsVisibleAtOnce() {
    String exe = data.trade(acc, "BUY", "SIMA", "1", "100.00", "WEB", "public note", true);
    assertThat(lk.feed.recordExecution(exe)).isTrue();
    assertThat(visible()).singleElement().satisfies(i -> {
      assertThat(i.kind()).isEqualTo(FeedItemKind.FILL);
      assertThat(i.payload().untrusted().rationale()).isEqualTo("public note");
    });
  }

  String transferId(String type) {
    return kit.db.jdbc().sql("SELECT public_id FROM cash_transfer WHERE account_id = ? AND transfer_type = ? "
        + "ORDER BY id DESC LIMIT 1").params(acc.id(), type).query(String.class).single();
  }

  @Test
  void fillsAppearAfterTheDelayWithOnlyPublicRationales() {
    lk = delayed();                                                 // the admin set PT15M
    PlayerRef bot = data.ai("claude", alice);
    AccountRef botAcc = data.open(bot, "500.00");
    String botExe = data.trade(botAcc, "BUY", "SIMA", "2", "100.00", "MCP_KEY", "Rotation into " + "y".repeat(500),
        true);
    String aliceExe = data.trade(acc, "BUY", "SIMA", "1", "100.00", "WEB", "secret", false);
    lk.feed.on(new ExecutionCreated(botAcc.id(), bot.id(), botExe, "ord", "SIMA", Side.BUY, new BigDecimal("2"),
        new BigDecimal("100.00"), new BigDecimal("-200.00"), MON_10));
    lk.feed.on(new ExecutionCreated(acc.id(), alice.id(), aliceExe, "ord", "SIMA", Side.BUY, BigDecimal.ONE,
        new BigDecimal("100.00"), new BigDecimal("-100.00"), MON_10));
    assertThat(lk.feed.recordExecution(botExe)).isFalse();          // a re-delivered event is a no-op
    assertThat(lk.feed.recordExecution("exe_missing")).isFalse();
    assertThat(visible()).isEmpty();                                // visible after social.tradeFeedDelay
    kit.at(MON_10.plus(Duration.ofMinutes(15)));
    List<FeedItemDto> items = visible();
    assertThat(items).hasSize(2).allSatisfy(i -> {
      assertThat(i.kind()).isEqualTo(FeedItemKind.FILL);
      assertThat(i.symbol()).isEqualTo("SIMA");
      assertThat(i.at()).isEqualTo(MON_10);
      assertThat(i.payload().side()).isEqualTo(Side.BUY);
      assertThat(i.payload().realizedPnl()).isNull();
    });
    FeedItemDto botItem = items.stream().filter(i -> i.player().handle().equals("claude")).findFirst().orElseThrow();
    assertThat(botItem.payload().qty()).isEqualByComparingTo("2");
    assertThat(botItem.payload().untrusted().rationale()).hasSize(280).startsWith("Rotation into");
    assertThat(botItem.id()).startsWith("fde_");
    FeedItemDto aliceItem = items.stream().filter(i -> i.player().handle().equals("alice")).findFirst()
        .orElseThrow();
    assertThat(aliceItem.payload().untrusted()).isNull();           // a private note never leaks
  }

  @Test
  void aSellToZeroIsAClosedPositionWithItsRealizedPnl() {
    data.buy(acc, "SIMA", "2", "100.00");
    kit.at(MON_10.plusSeconds(60));
    String partial = data.sell(acc, "SIMA", "1", "110.00");
    kit.at(MON_10.plusSeconds(120));
    String last = data.sell(acc, "SIMA", "1", "90.00");
    assertThat(lk.feed.recordExecution(partial)).isTrue();
    assertThat(lk.feed.recordExecution(last)).isTrue();
    kit.at(MON_10.plus(Duration.ofHours(1)));
    List<FeedItemDto> items = visible();
    assertThat(items).extracting(FeedItemDto::kind).containsExactly(FeedItemKind.POSITION_CLOSED, FeedItemKind.FILL);
    assertThat(items.get(0).payload().realizedPnl()).isEqualByComparingTo("-10.00");
    assertThat(items.get(1).payload().realizedPnl()).isEqualByComparingTo("10.00");
  }

  @Test
  void depositsAndJoinsArePublicAtOnceAndResetsAreKeyedByTheNewRun() {
    data.deposit(acc, "250.00");
    lk.feed.on(new CashTransferPosted(acc.id(), alice.id(), transferId("DEPOSIT"), "bat", TransferType.DEPOSIT,
        new BigDecimal("250.00"), "Halloween bonus", MON_10));
    assertThat(lk.feed.recordTransfer(transferId("INITIAL"))).isTrue();     // run 1's INITIAL: JOINED
    assertThat(lk.feed.recordTransfer(transferId("INITIAL"))).isFalse();
    data.withdraw(acc, "50.00");
    assertThat(lk.feed.recordTransfer(transferId("WITHDRAWAL"))).isTrue();
    assertThat(lk.feed.recordTransfer("xfr_missing")).isFalse();
    List<FeedItemDto> items = visible();
    assertThat(items).extracting(FeedItemDto::kind).containsExactlyInAnyOrder(FeedItemKind.DEPOSIT,
        FeedItemKind.DEPOSIT, FeedItemKind.JOINED);
    FeedItemDto deposit = items.stream().filter(i -> i.kind() == FeedItemKind.DEPOSIT
        && i.payload().amount().signum() > 0).findFirst().orElseThrow();
    assertThat(deposit.payload().amount()).isEqualByComparingTo("250.00");
    assertThat(deposit.payload().untrusted().memo()).isEqualTo("Admin deposit");
    assertThat(items).filteredOn(i -> i.kind() == FeedItemKind.DEPOSIT && i.payload().amount().signum() < 0)
        .singleElement().satisfies(w -> assertThat(w.payload().amount()).isEqualByComparingTo("-50.00"));

    AccountRef run2 = data.reset(acc, "500.00");
    lk.feed.on(new AccountChanged(acc.id(), alice.id(), "RESET", MON_10));
    lk.feed.on(new AccountChanged(run2.id(), alice.id(), "RESET", MON_10));   // both runs: one row
    lk.feed.on(new AccountChanged(run2.id(), alice.id(), "FILL", MON_10));
    String resetInitial = kit.db.jdbc().sql("SELECT public_id FROM cash_transfer WHERE account_id = ?")
        .param(run2.id()).query(String.class).single();
    assertThat(lk.feed.recordTransfer(resetInitial)).isFalse();     // a reset's INITIAL is not a JOIN
    assertThat(visible()).filteredOn(i -> i.kind() == FeedItemKind.RESET).singleElement()
        .satisfies(r -> assertThat(r.player().resets()).isEqualTo(1));
    assertThat(lk.feed.recordReset(data.human("solo").id(), MON_10)).isFalse();   // no second run
  }

  @Test
  void theOpenedEventAlsoYieldsJoinedOnceAndOnlyForTheFirstRun() {
    lk.feed.on(new AccountChanged(acc.id(), alice.id(), "OPENED", MON_10));     // WS3: no CashTransferPosted
    lk.feed.on(new AccountChanged(acc.id(), alice.id(), "OPENED", MON_10));
    assertThat(lk.feed.recordTransfer(transferId("INITIAL"))).isFalse();      // same JOIN:<ply> row
    AccountRef run2 = data.reset(acc, "500.00");
    lk.feed.on(new AccountChanged(run2.id(), alice.id(), "OPENED", MON_10));    // a reset's run is not a JOIN
    assertThat(lk.feed.recordOpening(-1L)).isFalse();
    assertThat(visible()).extracting(FeedItemDto::kind).containsExactly(FeedItemKind.JOINED);
  }

  @Test
  void depositsStayOutOfTheFeedWhenTheSettingSaysSo() {
    SettingsService noDeposits = (SettingsService) Proxy.newProxyInstance(SettingsService.class.getClassLoader(),
        new Class<?>[] {SettingsService.class}, (p, m, a) -> {
          if (m.getName().equals("showDepositsInFeed")) {
            return false;
          }
          try {
            return m.invoke(NotImplementedStub.of(SettingsService.class, "WS1"), a);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        });
    LeagueKit quiet = new LeagueKit(new PortfolioKit(kit.db, kit.time, NotImplementedStub.of(MarketCalendar.class,
        "WS2"), noDeposits));
    data.deposit(acc, "10.00");
    assertThat(quiet.feed.recordTransfer(transferId("DEPOSIT"))).isFalse();
  }

  @Test
  void newLeadersAreOnePerDayAndTheBackfillIsIdempotent() {
    assertThat(lk.feed.recordNewLeader(LocalDate.of(2026, 10, 5), alice.id(), LeaderboardPeriod.WEEK, MON_10))
        .isTrue();
    assertThat(lk.feed.recordNewLeader(LocalDate.of(2026, 10, 5), alice.id(), LeaderboardPeriod.WEEK, MON_10))
        .isFalse();
    data.buy(acc, "SIMA", "1", "100.00");
    data.deposit(acc, "5.00");
    Instant from = MON_10.minusSeconds(3600);
    Instant to = MON_10.plusSeconds(3600);
    assertThat(lk.feed.backfill(from, to)).isEqualTo(3);           // the fill, the deposit, the join
    assertThat(lk.feed.backfill(from, to)).isZero();
    kit.at(MON_10.plus(Duration.ofMinutes(30)));
    FeedItemDto leader = visible().stream().filter(i -> i.kind() == FeedItemKind.NEW_LEADER).findFirst()
        .orElseThrow();
    assertThat(leader.payload().period()).isEqualTo("WEEK");
  }

  @Test
  void pagesAreNewestFirstAndHideDisabledPlayers() {
    for (int i = 0; i < 3; i++) {
      kit.at(MON_10.plusSeconds(i));
      lk.feed.recordExecution(data.buy(acc, "SIMA", "1", "1.00"));
    }
    PlayerRef bob = data.human("bob");
    AccountRef b = data.open(bob, "100.00");
    lk.feed.recordExecution(data.buy(b, "SIMB", "1", "1.00"));
    kit.at(MON_10.plus(Duration.ofHours(1)));
    Page<FeedItemDto> first = lk.feedService.page(null, 2);
    assertThat(first.items()).hasSize(2);
    assertThat(first.nextCursor()).isNotNull();
    Page<FeedItemDto> second = lk.feedService.page(Cursor.parse(first.nextCursor()), 2);
    assertThat(second.items()).hasSize(2);
    assertThat(second.nextCursor()).isNull();
    assertThat(first.items().get(0).at()).isAfterOrEqualTo(second.items().get(0).at());
    data.fx.status(bob, "DISABLED");
    assertThat(lk.feedService.page(null, 50).items()).noneMatch(i -> i.player().handle().equals("bob"));
  }

  @Test
  void thePublisherPushesEachRowOnceWhenItBecomesVisible() {
    lk = delayed();                                                 // the admin set PT15M
    SimpMessageSendingOperations ops = mock(SimpMessageSendingOperations.class);
    assertThat(lk.feedService.publishNewlyVisible()).isZero();      // no broker: nothing to do
    lk.beans.addBean("brokerMessagingTemplate", ops);
    assertThat(lk.feedService.name()).isEqualTo("FEED_PUBLISHER");
    assertThat(lk.feedService.interval()).hasSeconds(5);
    assertThat(lk.feedService.sessionsOnly()).isFalse();
    lk.feedService.tick();                                          // sets the watermark
    lk.feed.recordExecution(data.buy(acc, "SIMA", "1", "1.00"));    // visible at +15 min
    kit.at(MON_10.plus(Duration.ofMinutes(10)));
    assertThat(lk.feedService.publishNewlyVisible()).isZero();
    kit.at(MON_10.plus(Duration.ofMinutes(16)));
    assertThat(lk.feedService.publishNewlyVisible()).isEqualTo(1);
    assertThat(lk.feedService.publishNewlyVisible()).isZero();
    verify(ops, times(1)).convertAndSend(eq(FeedService.TOPIC), any(FeedItemDto.class));
    verify(ops, never()).convertAndSend(eq("/topic/leaderboard"), any(Object.class));
  }

  /** At PT0S a row's visible_at falls before its asynchronous insert commits: the overlap still pushes it, once. */
  @Test
  void aRowCommittedAfterTheLastTickIsPushedExactlyOnce() {
    SimpMessageSendingOperations ops = mock(SimpMessageSendingOperations.class);
    lk.beans.addBean("brokerMessagingTemplate", ops);
    kit.at(MON_10.minusSeconds(5));
    lk.feedService.tick();                                          // the first tick only sets the watermark
    kit.at(MON_10);
    assertThat(lk.feedService.publishNewlyVisible()).isZero();      // the last tick: MON_10
    kit.at(MON_10.minusSeconds(2));                                 // executed 2 s before that tick …
    String exe = data.buy(acc, "SIMA", "1", "1.00");
    kit.at(MON_10.plusSeconds(1));
    lk.feed.recordExecution(exe);                                   // … and committed after it
    assertThat(visibleAt("EXEC:" + exe)).isEqualTo(MON_10.minusSeconds(2).toString());
    int pushed = 0;
    for (int tick = 1; tick <= 4; tick++) {
      kit.at(MON_10.plusSeconds(5L * tick));
      pushed += lk.feedService.publishNewlyVisible();
    }
    assertThat(pushed).isEqualTo(1);
    verify(ops, times(1)).convertAndSend(eq(FeedService.TOPIC), any(FeedItemDto.class));
  }

  /** The EOD backfill keeps a recovered fill at its original place in the feed: visible_at = executedAt + delay. */
  @Test
  void anEodBackfillRowKeepsItsOriginalVisibleAt() {
    LeagueKit slow = delayed();
    String exe = data.buy(acc, "SIMA", "1", "1.00");                 // executed at MON_10, its event lost
    kit.at(MON_10.plus(Duration.ofHours(10)));
    assertThat(slow.feed.backfill(MON_10.minusSeconds(60), kit.time.now())).isGreaterThanOrEqualTo(1);
    assertThat(visibleAt("EXEC:" + exe)).isEqualTo(MON_10.plus(Duration.ofMinutes(15)).toString());
  }
}
