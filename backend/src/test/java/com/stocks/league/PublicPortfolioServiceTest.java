package com.stocks.league;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.AccountStatus;
import com.stocks.common.domain.AccountType;
import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.Channel;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.config.JacksonConfig;
import com.stocks.contract.portfolio.PnlAnalysisQueries;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.CalendarView;
import com.stocks.contract.portfolio.dto.PublicOrderDto;
import com.stocks.contract.portfolio.dto.PublicPortfolioDto;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.ActivityFilter;
import com.stocks.contract.trading.OrderStatusFilter;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.ActorKind;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.contract.trading.dto.ResetDetailDto;
import com.stocks.contract.trading.dto.RestrictionDetailDto;
import com.stocks.portfolio.PortfolioData;
import com.stocks.portfolio.PortfolioKit;
import com.stocks.testkit.Fixtures.AccountRef;
import com.stocks.testkit.Fixtures.PlayerRef;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.SqlParameterValue;
import tools.jackson.databind.json.JsonMapper;

/**
 * Public mode (§16.7): {@link PublicPortfolioService} on a fresh database (H2, or embedded PostgreSQL under
 * {@code -Ppg-it}, which checks the typed null binds): any listed player's live portfolio and open orders, every
 * stored order across runs with fills, filters and cursor paging, the manual-console session id, public rationales
 * only, sanitized activity, and P&amp;L delegated to the active run. Account and position valuation come from a
 * mocked {@link AccountQueries} (WS3's own tests cover them); orders and fills are read from the database.
 */
class PublicPortfolioServiceTest {

  static final Instant MON_10 = Instant.parse("2026-10-05T14:00:00Z");
  static final String SESSION = "mbs_01J9ZB4X7Q3M5N8P2R6S4T0V1W";
  static final JsonMapper JSON = JacksonConfig.newMapper();

  PortfolioKit kit;
  PortfolioData data;
  PublicId ids;
  AccountQueries accounts = mock(AccountQueries.class);
  PnlAnalysisQueries pnl = mock(PnlAnalysisQueries.class);
  PublicPortfolioService service;
  PlayerRef alice;
  PlayerRef bot;
  AccountRef aliceAcc;
  AccountRef botAcc;
  int seq;

  @AfterEach
  void closeDb() {
    kit.close();
  }

  @BeforeEach
  void setUp() {
    kit = new PortfolioKit();
    kit.at(MON_10);
    data = kit.data;
    ids = new PublicId(kit.time);
    data.fx.asset("SIMA");
    data.fx.asset("SIMB");
    alice = data.human("alice");
    bot = data.ai("claude", alice);
    aliceAcc = data.open(alice, "1000.00");
    botAcc = data.open(bot, "1000.00");
    // like AccountQueriesImpl: the active run, else ACCOUNT_NOT_FOUND
    when(accounts.activeAccountId(anyLong())).thenAnswer(inv -> kit.repository.activeAccountId(inv.getArgument(0))
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "The player has no active account.")));
    service = new PublicPortfolioService(kit.players, accounts, pnl, kit.db.jdbc(), kit.time);
  }

  // ------------------------------------------------------------------------------------------- fixtures

  record Ord(long id, String publicId) {}

  /** One {@code trade_order} row received now; {@code limit} null for MARKET; open rows hold cash. */
  Ord order(AccountRef a, String symbol, String side, String limit, String qty, OrderStatus status, String channel,
            String mcpClient, String rationale, boolean rationalePublic) {
    String id = ids.next(Prefix.ORDER);
    Instant now = kit.time.now();
    boolean open = status.isOpen();
    kit.db.jdbc().sql("""
            INSERT INTO trade_order (public_id, account_id, player_id, client_order_id, request_hash, symbol, side,
                                     order_type, qty, fractional, limit_price, time_in_force, extended_hours, status,
                                     filled_qty, reserved_cash, session_date, received_at, accepted_at, eligible_at,
                                     expires_at, done_at, channel, mcp_client, client_ip, rationale, rationale_public)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, 'DAY', FALSE, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(id, a.id(), a.playerId(), "secret-cid-" + (++seq), "0".repeat(64), symbol, side,
            limit == null ? "MARKET" : "LIMIT", new BigDecimal(qty), Jdbc.decimalParam(limit == null ? null
                : new BigDecimal(limit)), status.name(),
            status == OrderStatus.FILLED ? new BigDecimal(qty) : BigDecimal.ZERO,
            open ? new BigDecimal("95.00") : BigDecimal.ZERO, kit.time.todayEt(), Jdbc.ts(now), Jdbc.ts(now),
            Jdbc.ts(now), Jdbc.ts(now.plusSeconds(6 * 3600)), Jdbc.tsParam(open ? null : now), channel,
            new SqlParameterValue(Types.VARCHAR, mcpClient), "203.0.113.7",
            new SqlParameterValue(Types.VARCHAR, rationale), rationalePublic)
        .update();
    long pk = kit.db.jdbc().sql("SELECT id FROM trade_order WHERE public_id = ?").param(id).query(Long.class)
        .single();
    return new Ord(pk, id);
  }

  Ord order(AccountRef a, String symbol, OrderStatus status) {
    return order(a, symbol, "BUY", "95.00", "1", status, "WEB", null, "note", true);
  }

  /** An execution of {@code o} now with SEC, TAF and CAT fees. */
  String fill(Ord o, AccountRef a, String symbol, String side, String qty, String price, String sec, String taf,
              String cat, String realized) {
    String exe = ids.next(Prefix.EXECUTION);
    BigDecimal principal = new BigDecimal(qty).multiply(new BigDecimal(price)).setScale(2);
    BigDecimal fees = new BigDecimal(sec).add(new BigDecimal(taf)).add(new BigDecimal(cat));
    BigDecimal net = "BUY".equals(side) ? principal.add(fees).negate() : principal.subtract(fees);
    kit.db.jdbc().sql("""
            INSERT INTO execution (public_id, order_id, account_id, symbol, side, qty, price, principal, fee_sec,
                                   fee_taf, fee_cat, net_amount, realized_pnl, liquidity, executed_at, trade_date,
                                   settle_date, ref_source, ref_quality)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TOUCH', ?, ?, ?, 'test', 'SIMULATED')""")
        .params(exe, o.id(), a.id(), symbol, side, new BigDecimal(qty), new BigDecimal(price), principal,
            new BigDecimal(sec), new BigDecimal(taf), new BigDecimal(cat), net,
            Jdbc.decimalParam(realized == null ? null : new BigDecimal(realized)), Jdbc.ts(kit.time.now()),
            kit.time.todayEt(), kit.time.todayEt().plusDays(1))
        .update();
    return exe;
  }

  static AccountDto account(String id, int runNo) {
    BigDecimal z = new BigDecimal("0.00");
    return new AccountDto(id, runNo, AccountType.CASH, AccountStatus.ACTIVE, AccountRestriction.SETTLED_CASH_ONLY,
        Instant.parse("2026-12-31T21:00:00Z"), "3 good-faith violations", new BigDecimal("1107.50"),
        new BigDecimal("507.50"), new BigDecimal("2.50"),
        new AccountDto.AccountCashDto(new BigDecimal("597.50"), new BigDecimal("497.50"), new BigDecimal("100.00"),
            new BigDecimal("402.50"), new BigDecimal("402.50")),
        new BigDecimal("95.00"), new BigDecimal("402.50"), new BigDecimal("1000.00"), new BigDecimal("1000.00"),
        new BigDecimal("107.50"), new BigDecimal("0.1075000000"), new BigDecimal("1.107500000000000000"),
        new BigDecimal("7.50"), new BigDecimal("0.0068181818"), new AccountDto.AccountGfvDto(3, null, 3, 4), 0,
        MON_10, DataQuality.REALTIME_SINGLE_VENUE, MON_10.plusSeconds(5));
  }

  static PositionDto position(String symbol, String marketValue, String unrealized) {
    return new PositionDto(symbol, symbol + " Inc.", new BigDecimal("5"), new BigDecimal("5"),
        new BigDecimal("50.00"), new BigDecimal("250.00"), new BigDecimal("51.00"), new BigDecimal("51.00"),
        DataQuality.REALTIME_SINGLE_VENUE, new BigDecimal(marketValue), new BigDecimal(unrealized), null, null, null,
        null, true, MON_10);
  }

  static ActivityDto activity(ActivityType type, ActorKind by, String memo,
                              com.stocks.contract.trading.dto.ActivityDetailDto detail) {
    return new ActivityDto("led_" + type, MON_10, type, null, null, null, null, null, new BigDecimal("1.00"),
        new BigDecimal("1001.00"), null, null, memo, by, null, null, null, detail);
  }

  static ErrorCode code(Runnable r) {
    try {
      r.run();
    } catch (ApiException e) {
      return e.code();
    }
    return null;
  }

  // ------------------------------------------------------------------------------------------- portfolio

  @Test
  void anotherPlayersLivePortfolioWithPositionsCashAndOpenOrders() {
    when(accounts.account(botAcc.id())).thenReturn(account(botAcc.publicId(), 1));
    when(accounts.positions(botAcc.id())).thenReturn(List.of(position("SIMB", "100.00", "-2.50"),
        position("SIMA", "407.50", "10.005")));
    Ord working = order(botAcc, "SIMA", "BUY", "95.00", "1", OrderStatus.WORKING, "MCP_KEY", "claude-code",
        "Buying the dip", true);
    kit.at(MON_10.plusSeconds(1));
    order(botAcc, "SIMB", OrderStatus.FILLED);
    order(aliceAcc, "SIMA", OrderStatus.WORKING);                    // another account's open order

    PublicPortfolioDto p = service.portfolio("CLAUDE");
    assertThat(p.player().handle()).isEqualTo("claude");
    assertThat(p.runNo()).isEqualTo(1);
    assertThat(p.asOf()).isEqualTo(MON_10.plusSeconds(5));
    assertThat(p.marksQuality()).isEqualTo(DataQuality.REALTIME_SINGLE_VENUE);
    assertThat(p.equity()).isEqualByComparingTo("1107.50");
    assertThat(p.cash()).isEqualByComparingTo("597.50");
    assertThat(p.settledCash()).isEqualByComparingTo("497.50");
    assertThat(p.unsettledCash()).isEqualByComparingTo("100.00");
    assertThat(p.buyingPower()).isEqualByComparingTo("402.50");
    assertThat(p.openBuyHolds()).isEqualByComparingTo("95.00");
    assertThat(p.dividendsReceivable()).isEqualByComparingTo("2.50");
    assertThat(p.restriction()).isEqualTo(AccountRestriction.SETTLED_CASH_ONLY);
    assertThat(p.restrictionUntil()).isEqualTo(Instant.parse("2026-12-31T21:00:00Z"));
    assertThat(p.gfvActive()).isEqualTo(3);
    assertThat(p.unrealizedPnl()).isEqualTo(new BigDecimal("7.50"));           // Σ, scale 2
    assertThat(p.positions()).extracting(PositionDto::symbol).containsExactly("SIMA", "SIMB");   // by value
    assertThat(p.openOrders()).singleElement().satisfies(o -> {
      assertThat(o.orderId()).isEqualTo(working.publicId());
      assertThat(o.status()).isEqualTo(OrderStatus.WORKING);
      assertThat(o.limitPrice()).isEqualByComparingTo("95.00");
      assertThat(o.channel()).isEqualTo(Channel.MCP_KEY);
      assertThat(o.viaApi()).isTrue();
      assertThat(o.manualSessionId()).isNull();
      assertThat(o.untrusted().rationale()).isEqualTo("Buying the dip");
      assertThat(o.fills()).isEmpty();
    });

    String json = JSON.writeValueAsString(p) + JSON.writeValueAsString(service.orders("claude", null, null, null, 50));
    for (String secret : List.of("clientOrderId", "accountId", "reservedCash", "reservedQty", "mcpClient",
        "clientIp", "apiKeyId", "oauthClientId", "restrictionReason", "secret-cid", "claude-code", "203.0.113.7",
        botAcc.publicId(), "3 good-faith violations")) {
      assertThat(json).as(secret).doesNotContain(secret);
    }
  }

  @Test
  void aPlayerWithoutARunHasAnEmptyPortfolioAndDisabledPlayersAreHidden() {
    PlayerRef newbie = data.human("newbie");
    PublicPortfolioDto empty = service.portfolio("newbie");
    assertThat(empty.runNo()).isZero();
    assertThat(empty.asOf()).isEqualTo(MON_10);
    assertThat(empty.equity()).isEqualTo(new BigDecimal("0.00"));
    assertThat(empty.cash()).isEqualTo(new BigDecimal("0.00"));
    assertThat(empty.totalReturn()).isNull();
    assertThat(empty.dayReturn()).isNull();
    assertThat(empty.marksQuality()).isNull();
    assertThat(empty.restriction()).isNull();
    assertThat(empty.positions()).isEmpty();
    assertThat(empty.openOrders()).isEmpty();
    assertThat(service.activities("newbie", new ActivityFilter(Set.of(), null, null), null, 30).items()).isEmpty();
    assertThat(service.orders("newbie", null, null, null, 50).items()).isEmpty();

    when(accounts.account(botAcc.id())).thenReturn(account(botAcc.publicId(), 1));
    data.fx.status(bot, "DISABLED");
    assertThat(code(() -> service.portfolio("claude"))).isEqualTo(ErrorCode.PLAYER_NOT_FOUND);
    assertThat(code(() -> service.orders("claude", null, null, null, 50))).isEqualTo(ErrorCode.PLAYER_NOT_FOUND);
    assertThat(code(() -> service.activities("claude", null, null, 50))).isEqualTo(ErrorCode.PLAYER_NOT_FOUND);
    assertThat(code(() -> service.pnlAnalysis("claude", null))).isEqualTo(ErrorCode.PLAYER_NOT_FOUND);
    assertThat(service.portfolioAnyStatus("claude").runNo()).as("the admin twin").isEqualTo(1);
    assertThat(code(() -> service.portfolioAnyStatus("nobody"))).isEqualTo(ErrorCode.PLAYER_NOT_FOUND);
    assertThat(code(() -> service.portfolio("nobody"))).isEqualTo(ErrorCode.PLAYER_NOT_FOUND);
    assertThat(newbie.handle()).isEqualTo("newbie");
  }

  // ------------------------------------------------------------------------------------------- orders

  @Test
  void theManualSessionIdComesOnlyFromAWebOrderOfTheConsole() {
    Ord manual = order(botAcc, "SIMA", "BUY", "95.00", "1", OrderStatus.WORKING, "WEB", "manual-console " + SESSION,
        "From the console", true);
    Ord faked = order(botAcc, "SIMA", "BUY", "95.00", "1", OrderStatus.WORKING, "MCP_KEY",
        "manual-console " + SESSION, "Pretending", true);
    Ord junk = order(botAcc, "SIMA", "BUY", "95.00", "1", OrderStatus.WORKING, "WEB", "manual-console mbs_nope",
        "Junk", true);
    Ord plain = order(botAcc, "SIMA", OrderStatus.WORKING);
    List<PublicOrderDto> items = service.orders("claude", OrderStatusFilter.OPEN, null, null, 50).items();
    assertThat(items).filteredOn(o -> o.orderId().equals(manual.publicId())).singleElement()
        .satisfies(o -> {
          assertThat(o.manualSessionId()).isEqualTo(SESSION);
          assertThat(o.channel()).isEqualTo(Channel.WEB);
          assertThat(o.viaApi()).isFalse();
        });
    assertThat(items).filteredOn(o -> o.orderId().equals(faked.publicId())).singleElement()
        .satisfies(o -> assertThat(o.manualSessionId()).as("an MCP client cannot fake it").isNull());
    assertThat(items).filteredOn(o -> o.orderId().equals(junk.publicId())).singleElement()
        .satisfies(o -> assertThat(o.manualSessionId()).isNull());
    assertThat(items).filteredOn(o -> o.orderId().equals(plain.publicId())).singleElement()
        .satisfies(o -> assertThat(o.manualSessionId()).isNull());
    assertThat(PublicPortfolioService.manualSessionId(Channel.WEB, null)).isNull();
  }

  @Test
  void rationalesAreFullWhenPublicAndNullWhenWrittenAsPrivate() {
    String longNote = "Thesis " + "x".repeat(593);
    Ord pub = order(aliceAcc, "SIMA", "BUY", "95.00", "1", OrderStatus.WORKING, "WEB", null, longNote, true);
    Ord priv = order(aliceAcc, "SIMA", "BUY", "95.00", "1", OrderStatus.WORKING, "WEB", null, "my secret plan",
        false);
    List<PublicOrderDto> items = service.orders("alice", null, null, null, 50).items();
    assertThat(items).filteredOn(o -> o.orderId().equals(pub.publicId())).singleElement()
        .satisfies(o -> assertThat(o.untrusted().rationale()).hasSize(600).isEqualTo(longNote));   // REST: full
    assertThat(items).filteredOn(o -> o.orderId().equals(priv.publicId())).singleElement()
        .satisfies(o -> assertThat(o.untrusted().rationale()).isNull());
    assertThat(JSON.writeValueAsString(items)).doesNotContain("my secret plan");
  }

  @Test
  void aPendingCancelAndABracketsHeldLegMapLikeOrders() {
    Ord cancelling = order(aliceAcc, "SIMA", OrderStatus.WORKING);
    kit.db.jdbc().sql("UPDATE trade_order SET cancel_requested_at = ?, cancel_effective_at = ? WHERE id = ?")
        .params(Jdbc.ts(MON_10), Jdbc.ts(MON_10.plusSeconds(2)), cancelling.id()).update();
    String grp = ids.next(Prefix.ORDER_GROUP);
    kit.db.jdbc().sql("""
            INSERT INTO order_group (public_id, account_id, player_id, client_order_id, request_hash, group_type,
                                     symbol, qty, time_in_force, created_at, updated_at)
            VALUES (?, ?, ?, 'g-1', ?, 'BRACKET', 'SIMB', 2, 'DAY', ?, ?)""")
        .params(grp, aliceAcc.id(), alice.id(), "0".repeat(64), Jdbc.ts(MON_10), Jdbc.ts(MON_10)).update();
    long groupId = kit.db.jdbc().sql("SELECT id FROM order_group WHERE public_id = ?").param(grp)
        .query(Long.class).single();
    kit.at(MON_10.plusSeconds(1));
    Ord entry = order(aliceAcc, "SIMB", "BUY", "100.00", "2", OrderStatus.WORKING, "WEB", null, "Bracket", true);
    Ord tp = order(aliceAcc, "SIMB", "SELL", "110.00", "2", OrderStatus.PENDING, "WEB", null, "Bracket", true);
    kit.db.jdbc().sql("UPDATE trade_order SET group_id = ?, group_role = 'ENTRY' WHERE id = ?")
        .params(groupId, entry.id()).update();
    kit.db.jdbc().sql("UPDATE trade_order SET group_id = ?, group_role = 'TAKE_PROFIT', parent_order_id = ?, "
            + "held = TRUE, wait_reason = 'AWAITING_PARENT' WHERE id = ?")
        .params(groupId, entry.id(), tp.id()).update();

    List<PublicOrderDto> items = service.orders("alice", OrderStatusFilter.OPEN, null, null, 50).items();
    assertThat(items).filteredOn(o -> o.orderId().equals(cancelling.publicId())).singleElement()
        .satisfies(o -> assertThat(o.status()).isEqualTo(OrderStatus.PENDING_CANCEL));
    assertThat(items).filteredOn(o -> o.orderId().equals(tp.publicId())).singleElement().satisfies(o -> {
      assertThat(o.waitReason()).isEqualTo(WaitReason.AWAITING_PARENT);
      assertThat(o.group().groupId()).isEqualTo(grp);
      assertThat(o.group().type()).isEqualTo(OrderGroupType.BRACKET);
      assertThat(o.group().role()).isEqualTo(OrderGroupRole.TAKE_PROFIT);
      assertThat(o.group().parentOrderId()).isEqualTo(entry.publicId());
      assertThat(o.group().held()).isTrue();
    });
    assertThat(items).filteredOn(o -> o.orderId().equals(entry.publicId())).singleElement().satisfies(o -> {
      assertThat(o.group().role()).isEqualTo(OrderGroupRole.ENTRY);
      assertThat(o.group().parentOrderId()).isNull();
      assertThat(o.group().held()).isFalse();
    });
  }

  @Test
  void everyOrderOfEveryRunPagesNewestFirstWithFiltersAndFills() {
    // run 1: 30 filled orders (SIMA, then SIMB every third), one of them with a fill paying fees
    Ord withFill = null;
    for (int i = 0; i < 30; i++) {
      kit.at(MON_10.plusSeconds(i));
      Ord o = order(botAcc, i % 3 == 0 ? "SIMB" : "SIMA", OrderStatus.FILLED);
      if (i == 0) {
        withFill = o;
      }
    }
    fill(withFill, botAcc, "SIMB", "BUY", "1", "95.00", "0.00", "0.00", "0.01", null);
    String boughtId = withFill.publicId();
    AccountRef run2 = data.reset(botAcc, "1000.00");
    // run 2: 20 orders, the last 5 still working; one sells with fees and realized P&L
    Ord sold = null;
    for (int i = 0; i < 20; i++) {
      kit.at(MON_10.plusSeconds(100 + i));
      Ord o = order(run2, "SIMA", i >= 15 ? OrderStatus.WORKING : OrderStatus.FILLED);
      if (i == 3) {
        sold = o;
      }
    }
    fill(sold, run2, "SIMA", "SELL", "1", "100.00", "0.01", "0.02", "0.01", "4.96");
    fill(sold, run2, "SIMA", "SELL", "1", "101.00", "0.01", "0.00", "0.00", "5.99");

    Page<PublicOrderDto> first = service.orders("claude", OrderStatusFilter.ALL, null, null, 25);
    assertThat(first.items()).hasSize(25);
    assertThat(first.items()).extracting(PublicOrderDto::runNo).containsOnly(2, 1);
    assertThat(first.items().get(0).runNo()).isEqualTo(2);
    assertThat(first.items().get(0).receivedAt()).isEqualTo(MON_10.plusSeconds(119));
    assertThat(first.nextCursor()).isNotNull();
    Page<PublicOrderDto> second = service.orders("claude", null, null, Cursor.parse(first.nextCursor()), 25);
    assertThat(second.items()).hasSize(25).extracting(PublicOrderDto::runNo).containsOnly(1);
    assertThat(second.nextCursor()).isNull();
    List<String> all = new ArrayList<>(first.items().stream().map(PublicOrderDto::orderId).toList());
    all.addAll(second.items().stream().map(PublicOrderDto::orderId).toList());
    assertThat(all).doesNotHaveDuplicates().hasSize(50);

    assertThat(service.orders("claude", OrderStatusFilter.OPEN, null, null, 50).items()).hasSize(5)
        .allSatisfy(o -> assertThat(o.status()).isEqualTo(OrderStatus.WORKING));
    assertThat(service.orders("claude", OrderStatusFilter.CLOSED, null, null, 50).items()).hasSize(45);
    assertThat(service.orders("claude", null, " simb ", null, 50).items()).hasSize(10)
        .allSatisfy(o -> assertThat(o.symbol()).isEqualTo("SIMB"));
    assertThat(service.orders("claude", OrderStatusFilter.OPEN, "SIMB", null, 50).items()).isEmpty();

    String soldId = sold.publicId();
    PublicOrderDto s = service.orders("claude", OrderStatusFilter.CLOSED, "SIMA", null, 50).items().stream()
        .filter(o -> o.orderId().equals(soldId)).findFirst().orElseThrow();
    assertThat(s.runNo()).isEqualTo(2);
    assertThat(s.fills()).hasSize(2);
    assertThat(s.fills().get(0).price()).isEqualByComparingTo("100.00");
    assertThat(s.fills().get(0).fees()).isEqualByComparingTo("0.04");             // SEC + TAF + CAT
    assertThat(s.fills().get(0).realizedPnl()).isEqualByComparingTo("4.96");
    assertThat(s.fills().get(0).executionId()).startsWith("exe_");
    assertThat(s.fills().get(1).fees()).isEqualByComparingTo("0.01");
    assertThat(s.fills().get(0).qty()).isEqualTo(new BigDecimal("1"));
    PublicOrderDto b = second.items().get(second.items().size() - 1);          // the oldest order
    assertThat(b.orderId()).isEqualTo(boughtId);
    assertThat(b.fills()).singleElement().satisfies(f -> assertThat(f.realizedPnl()).isNull());
    assertThat(b.qty()).isEqualTo(new BigDecimal("1"));
    assertThat(b.filledQty()).isEqualTo(new BigDecimal("1"));
  }

  // ------------------------------------------------------------------------------------------- activity

  @Test
  void activityLeavesOutAdminViewsMemosAndAccountIds() {
    ResetDetailDto reset = new ResetDetailDto(ResetDetailDto.ResetSide.OPENED, "acc_OLD", "acc_NEW", 2,
        new BigDecimal("900.00"), new BigDecimal("1000.00"), "fresh start");
    RestrictionDetailDto byAdmin = new RestrictionDetailDto(AccountRestriction.NONE, AccountRestriction.FROZEN, null,
        "under review");
    RestrictionDetailDto bySystem = new RestrictionDetailDto(AccountRestriction.NONE,
        AccountRestriction.SETTLED_CASH_ONLY, Instant.parse("2027-01-04T14:30:00Z"), "3 good-faith violations");
    ArgumentCaptor<ActivityFilter> filter = ArgumentCaptor.forClass(ActivityFilter.class);
    when(accounts.activities(eq(aliceAcc.id()), filter.capture(), any(), anyInt())).thenReturn(new Page<>(List.of(
        activity(ActivityType.FILL, ActorKind.SELF, "my memo", null),
        activity(ActivityType.DEPOSIT, ActorKind.ADMIN, "Halloween bonus", null),
        activity(ActivityType.DIVIDEND, ActorKind.SYSTEM, "Dividend correction", null),
        activity(ActivityType.RESET, ActorKind.SELF, "fresh start", reset),
        activity(ActivityType.RESTRICTION, ActorKind.ADMIN, null, byAdmin),
        activity(ActivityType.RESTRICTION, ActorKind.SYSTEM, null, bySystem),
        // after a reset the new run's funding row (SYSTEM) carries the resetting admin's memo
        activity(ActivityType.INITIAL_FUNDING, ActorKind.SYSTEM, "Reset after GFV abuse, complaint from @bob", null),
        activity(ActivityType.ADJUSTMENT, ActorKind.SYSTEM, "an unexpected system memo", null)), "c1"));

    Page<ActivityDto> page = service.activities("alice", new ActivityFilter(Set.of(), null, null), null, 30);
    assertThat(filter.getValue().typesOrEmpty()).isEqualTo(EnumSet.complementOf(EnumSet.of(ActivityType.ADMIN_VIEW)));
    assertThat(page.nextCursor()).isEqualTo("c1");
    List<ActivityDto> rows = page.items();
    assertThat(rows.get(0).memo()).as("a player's memo").isNull();
    assertThat(rows.get(1).memo()).as("an admin's memo").isNull();
    assertThat(rows.get(2).memo()).as("a system memo").isEqualTo("Dividend correction");
    assertThat(rows.get(3).detail()).isInstanceOfSatisfying(ResetDetailDto.class, r -> {
      assertThat(r.fromAccountId()).isNull();
      assertThat(r.toAccountId()).isNull();
      assertThat(r.memo()).isNull();
      assertThat(r.runNo()).isEqualTo(2);
      assertThat(r.finalEquity()).isEqualByComparingTo("900.00");
    });
    assertThat(rows.get(4).detail()).isInstanceOfSatisfying(RestrictionDetailDto.class, r -> {
      assertThat(r.reason()).isNull();
      assertThat(r.to()).isEqualTo(AccountRestriction.FROZEN);
    });
    assertThat(rows.get(5).detail()).isInstanceOfSatisfying(RestrictionDetailDto.class,
        r -> assertThat(r.reason()).isEqualTo("3 good-faith violations"));
    assertThat(rows.get(6).memo()).as("a reset memo on the funding row").isNull();
    assertThat(rows.get(7).memo()).as("a system memo on a type the system does not write").isNull();
    String json = JSON.writeValueAsString(page);
    assertThat(json).doesNotContain("acc_OLD").doesNotContain("acc_NEW").doesNotContain("under review")
        .doesNotContain("my memo").doesNotContain("Halloween").doesNotContain("fresh start")
        .doesNotContain("GFV abuse").doesNotContain("unexpected");

    // ADMIN_VIEW is never public, even asked for alone
    assertThat(service.activities("alice", new ActivityFilter(Set.of(ActivityType.ADMIN_VIEW), null, null), null,
        30).items()).isEmpty();
    verify(accounts).activities(anyLong(), any(), any(), anyInt());              // only the call above
    service.activities("alice", new ActivityFilter(Set.of(ActivityType.FILL, ActivityType.ADMIN_VIEW), null, null),
        null, 30);
    assertThat(filter.getValue().typesOrEmpty()).containsExactly(ActivityType.FILL);
  }

  // ------------------------------------------------------------------------------------------- P&L

  @Test
  void pnlDelegatesToTheActiveRunAndNeedsOne() {
    service.pnlAnalysis("alice", null);
    verify(pnl).analysis(aliceAcc.id(), HistoryRange.M1);
    service.pnlAnalysis("alice", HistoryRange.W1);
    verify(pnl).analysis(aliceAcc.id(), HistoryRange.W1);
    service.pnlCalendar("alice", null, null, null);
    verify(pnl).calendar(aliceAcc.id(), CalendarView.DAY, null, null);
    service.pnlCalendar("claude", CalendarView.MONTH, YearMonth.of(2026, 10), 2026);
    verify(pnl).calendar(botAcc.id(), CalendarView.MONTH, YearMonth.of(2026, 10), 2026);

    data.human("newbie");
    assertThatThrownBy(() -> service.pnlAnalysis("newbie", null)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
      assertThat(e.detail()).isEqualTo("@newbie has no active run.");
    });
    assertThat(code(() -> service.pnlCalendar("newbie", null, null, null))).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
  }
}
