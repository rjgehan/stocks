package com.stocks.trading.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.AdjustmentTreatment;
import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.TransferScope;
import com.stocks.common.domain.TransferStatus;
import com.stocks.common.domain.TransferType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.events.CashTransferPosted;
import com.stocks.contract.trading.ActivityFilter;
import com.stocks.contract.trading.CashTransferCommand;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.TransferFilter;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.CashTransferPreviewDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.ResetDetailDto;
import com.stocks.contract.trading.dto.TransferBatchResultDto;
import com.stocks.testkit.TestTimes;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Accounts and admin money (§10.1, §10.5, §16.2, §21.2): open (idempotent, default starting cash), deposits,
 * withdrawals and adjustments to ONE player as ledger rows (never balance edits) with unit accounting, the
 * withdrawable check and all-or-nothing, in-engine idempotency, the mark-quality gate, reset to run n+1 at the final
 * unit value, freeze/unfreeze, and the merged activity feed.
 */
@RecordApplicationEvents
class AccountAndTransferTest extends TradingTestBase {

  static final String KEY_SCOPE = TransferCommands.SCOPE;

  @Autowired ApplicationEvents events;

  @Test
  void openingFundsRunOneOnceAtUnitValueOne() {
    Player p = human();
    AccountDto a = account(p);
    assertThat(a.runNo()).isEqualTo(1);
    assertThat(a.equity()).isEqualByComparingTo("500.00");
    assertThat(a.cash().total()).isEqualByComparingTo("500.00");
    assertThat(a.cash().settled()).isEqualByComparingTo("500.00");
    assertThat(a.cash().withdrawable()).isEqualByComparingTo("500.00");
    assertThat(a.netContributions()).isEqualByComparingTo("500.00");
    assertThat(a.unitValue()).isEqualByComparingTo("1");
    assertThat(a.totalPnl()).isEqualByComparingTo("0");
    assertThat(a.gfv().active()).isZero();
    // idempotent per player: the signup hook may run twice
    assertThat(accountService.openInitialAccount(p.id(), new BigDecimal("999.00"), p.id()).accountId())
        .isEqualTo(a.accountId());
    assertThat(activities(p)).singleElement().satisfies(x -> {
      assertThat(x.type()).isEqualTo(ActivityType.INITIAL_FUNDING);
      assertThat(x.amount()).isEqualByComparingTo("500.00");
      assertThat(x.balanceAfter()).isEqualByComparingTo("500.00");
    });
    // a null starting cash takes league.defaultStartingCash (500.00)
    Player q = new Player(fx.human("d" + next()), 0, null);
    assertThat(accountService.openInitialAccount(q.id(), null, q.id()).equity()).isEqualByComparingTo("500.00");
  }

  @Test
  void aDepositToOnePlayerIsALedgerRowThatSettlesImmediately() {
    Player p = human();
    TransferBatchResultDto r = transfers.apply(single(p, TransferType.DEPOSIT, "250.00", "Halloween bonus"), null,
        admin());
    assertThat(r.applied()).isEqualTo(1);
    assertThat(r.skipped()).isZero();
    assertThat(r.results()).singleElement().satisfies(x -> {
      assertThat(x.status()).isEqualTo(TransferStatus.APPLIED);
      assertThat(x.cashAfter()).isEqualByComparingTo("750.00");
      assertThat(x.equityBefore()).isEqualByComparingTo("500.00");
      assertThat(x.unitValue()).isEqualByComparingTo("1");
    });
    AccountDto a = account(p);
    assertThat(a.cash().total()).isEqualByComparingTo("750.00");
    assertThat(a.cash().settled()).isEqualByComparingTo("750.00");
    assertThat(a.netContributions()).isEqualByComparingTo("750.00");
    assertThat(a.totalPnl()).as("deposits are not P&L").isEqualByComparingTo("0");
    assertThat(a.unitValue()).as("a flow never changes U").isEqualByComparingTo("1");
    assertThat(units(p)).isEqualByComparingTo("750");
    assertThat(activities(p)).first().satisfies(x -> {
      assertThat(x.type()).isEqualTo(ActivityType.DEPOSIT);
      assertThat(x.memo()).isEqualTo("Halloween bonus");
      assertThat(x.settleDate()).isEqualTo(TestTimes.TRADE_DATE);
    });
    assertThat(events.stream(CashTransferPosted.class).filter(e -> e.accountId() == p.accountId()))
        .singleElement().satisfies(e -> {
          assertThat(e.amount()).isEqualByComparingTo("250.00");
          assertThat(e.type()).isEqualTo(TransferType.DEPOSIT);
        });
    assertThat(events.stream(AccountChanged.class).filter(e -> e.accountId() == p.accountId())
        .map(AccountChanged::reason)).contains("TRANSFER");
    assertThat(transfers.list(new TransferFilter(p.handle(), null, null, null), null, 10).items())
        .extracting(t -> t.type()).containsExactly(TransferType.DEPOSIT, TransferType.INITIAL);
  }

  @Test
  void aDepositAfterAGainMintsUnitsAtTheCurrentUnitValue() {
    Player p = human();
    String s = symbol();
    buy(p, s, "4", "100.00");                            // cash 100, 4 shares
    print(s, "125.00", 100);                              // equity 100 + 500 = 600 → U = 1.2
    AccountDto before = account(p);
    assertThat(before.equity()).isEqualByComparingTo("600.00");
    assertThat(before.unitValue()).isEqualByComparingTo("1.2");
    transfers.apply(single(p, TransferType.DEPOSIT, "120.00", "top-up"), null, admin());
    assertThat(units(p)).isEqualByComparingTo("600");    // 500 + 120 / 1.2
    AccountDto after = account(p);
    assertThat(after.unitValue()).isEqualByComparingTo("1.2");
    assertThat(after.totalPnl()).isEqualByComparingTo("100.00");
    assertThat(after.totalReturn()).isEqualByComparingTo("0.2");
  }

  @Test
  void aWithdrawalBeyondWithdrawableIsSkippedOrRolledBack() {
    Player p = human();
    String s = symbol();
    buy(p, s, "3", "100.00");                             // cash 200 (settled 500, unsettled −300)
    assertThat(account(p).cash().withdrawable()).isEqualByComparingTo("200.00");
    TransferBatchResultDto skipped = transfers.apply(single(p, TransferType.WITHDRAWAL, "250.00", "too much"), null,
        admin());
    assertThat(skipped.applied()).isZero();
    assertThat(skipped.results()).singleElement().satisfies(x -> {
      assertThat(x.status()).isEqualTo(TransferStatus.SKIPPED);
      assertThat(x.skipReason()).isEqualTo("INSUFFICIENT_WITHDRAWABLE");
      assertThat(x.cashAfter()).isEqualByComparingTo("200.00");
    });
    CashTransferCommand all = new CashTransferCommand(TransferScope.SINGLE, p.handle(), Set.of(),
        TransferType.WITHDRAWAL, new BigDecimal("250.00"), null, "strict", true, false);
    long batches = count("transfer_batch");
    assertThatThrownBy(() -> transfers.apply(all, null, admin())).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_WITHDRAWABLE);
      assertThat(e.fields()).extracting(f -> f.field()).containsExactly(p.handle());
    });
    assertThat(count("transfer_batch")).as("all-or-nothing rolls the batch back").isEqualTo(batches);
    TransferBatchResultDto ok = transfers.apply(single(p, TransferType.WITHDRAWAL, "200.00", "cash out"), null,
        admin());
    assertThat(ok.applied()).isEqualTo(1);
    assertThat(account(p).cash().total()).isEqualByComparingTo("0.00");
    assertThat(account(p).netContributions()).isEqualByComparingTo("300.00");
  }

  @Test
  void aPnlCorrectionAdjustmentIsNotAContribution() {
    Player p = human();
    CashTransferCommand c = new CashTransferCommand(TransferScope.SINGLE, p.handle(), Set.of(),
        TransferType.ADJUSTMENT, new BigDecimal("-12.34"), AdjustmentTreatment.PNL_CORRECTION, "fix", false, false);
    transfers.apply(c, null, admin());
    AccountDto a = account(p);
    assertThat(a.cash().total()).isEqualByComparingTo("487.66");
    assertThat(a.netContributions()).isEqualByComparingTo("500.00");
    assertThat(units(p)).isEqualByComparingTo("500");
    assertThat(a.totalPnl()).isEqualByComparingTo("-12.34");
    assertThat(activities(p)).first().satisfies(x -> assertThat(x.type()).isEqualTo(ActivityType.ADJUSTMENT));
  }

  /**
   * Review regression: an account emptied by a full withdrawal holds zero units. A PnL correction into it used to add
   * equity no unit value could show, and a later withdrawal then computed negative units and failed the whole batch on
   * ck_account_units_nonneg (INTERNAL_ERROR). Both now skip with NO_UNITS; a contribution inflow still applies. Runs
   * on PostgreSQL too under -Ppg-it. The ALL-scope half is {@link TransferToAllTest}.
   */
  @Test
  void anAccountWithoutUnitsSkipsOutflowsAndPnlCorrectionsButTakesDeposits() {
    Player p = human();
    long admin = admin();
    assertThat(transfers.apply(single(p, TransferType.WITHDRAWAL, "500.00", "cash out"), null, admin).applied())
        .isEqualTo(1);
    assertThat(units(p)).isEqualByComparingTo("0");

    CashTransferCommand correction = new CashTransferCommand(TransferScope.SINGLE, p.handle(), Set.of(),
        TransferType.ADJUSTMENT, new BigDecimal("10.00"), AdjustmentTreatment.PNL_CORRECTION, "fix", false, false);
    assertThat(transfers.preview(correction).accounts()).singleElement().satisfies(x -> {
      assertThat(x.wouldApply()).isFalse();
      assertThat(x.skipReason()).isEqualTo(TransferPlanner.SKIP_NO_UNITS);
    });
    TransferBatchResultDto c = transfers.apply(correction, null, admin);
    assertThat(c.applied()).isZero();
    assertThat(c.results()).singleElement().satisfies(x -> {
      assertThat(x.status()).isEqualTo(TransferStatus.SKIPPED);
      assertThat(x.skipReason()).isEqualTo("NO_UNITS");
    });
    assertThat(account(p).cash().total()).isEqualByComparingTo("0.00");

    // a deposit mints units again at the last transfer's unit value
    assertThat(transfers.apply(single(p, TransferType.DEPOSIT, "20.00", "back in"), null, admin).applied())
        .isEqualTo(1);
    assertThat(units(p)).isEqualByComparingTo("20");
    assertThat(account(p).equity()).isEqualByComparingTo("20.00");
  }

  @Test
  void transfersAreIdempotentPerKey() {
    Player p = human();
    CashTransferCommand c = single(p, TransferType.DEPOSIT, "10.00", "once");
    IdempotencyRef idem = new IdempotencyRef(KEY_SCOPE, "k-" + next(), "hash-a");
    TransferBatchResultDto first = transfers.apply(c, idem, admin());
    TransferBatchResultDto again = transfers.apply(c, idem, admin());
    assertThat(again.batchId()).isEqualTo(first.batchId());
    assertThat(account(p).cash().total()).isEqualByComparingTo("510.00");
    assertThatThrownBy(() -> transfers.apply(single(p, TransferType.DEPOSIT, "11.00", "once"),
        new IdempotencyRef(KEY_SCOPE, idem.key(), "hash-b"), admin()))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
  }

  @Test
  void theMarkQualityGateRejectsDegradedMarksUnlessAccepted() {
    Player p = human();
    String s = "S" + next();
    md.withAsset(s).prevClose(s, "10.00");               // no print, no quote: the mark is the previous close
    fx.position(new com.stocks.testkit.Fixtures.AccountRef(p.accountId(), "acc", p.id()), s, "2", "10.00");
    CashTransferPreviewDto preview = transfers.preview(single(p, TransferType.DEPOSIT, "5.00", "gate"));
    assertThat(preview.accounts()).singleElement().satisfies(a -> {
      assertThat(a.wouldApply()).isTrue();
      assertThat(a.worstMarkBasis()).isEqualTo("PREV_CLOSE");
      assertThat(a.warnings()).extracting(w -> w.code()).contains("MARKS_DEGRADED");
    });
    long before = count("cash_transfer");
    assertThatThrownBy(() -> transfers.apply(single(p, TransferType.DEPOSIT, "5.00", "gate"), null, admin()))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCode.MARKS_DEGRADED);
          assertThat(e.status()).isEqualTo(422);
          assertThat(e.fields()).extracting(f -> f.field()).containsExactly(p.handle());
        });
    assertThat(count("cash_transfer")).isEqualTo(before);
    CashTransferCommand accepted = new CashTransferCommand(TransferScope.SINGLE, p.handle(), Set.of(),
        TransferType.DEPOSIT, new BigDecimal("5.00"), null, "gate", false, true);
    assertThat(transfers.apply(accepted, null, admin()).applied()).isEqualTo(1);
    // a live print makes the mark good again
    print(s, "10.50", 100);
    assertThat(transfers.apply(single(p, TransferType.DEPOSIT, "5.00", "live"), null, admin()).applied())
        .isEqualTo(1);
  }

  @Test
  void resetClosesTheRunAndOpensTheNextAtTheFinalUnitValue() {
    Player p = human();
    String s = symbol();
    buy(p, s, "2", "100.00");                             // cash 300
    print(s, "150.00", 100);                              // equity 300 + 300 = 600, U = 1.2
    quote(s, "149.98", 300, "150.00", 300);
    OrderDto open = place(p, limit(cid(), s, Side.BUY, "1", "140.00", TimeInForce.GTC, false)).order();
    String oldAccount = account(p).accountId();
    IdempotencyRef idem = new IdempotencyRef(AccountCommands.RESET_SCOPE_ADMIN, "r-" + next(), "h");
    AccountDto run2 = accountService.reset(p.id(), null, "fresh start", idem, admin());
    assertThat(run2.runNo()).isEqualTo(2);
    assertThat(run2.accountId()).isNotEqualTo(oldAccount);
    assertThat(run2.equity()).isEqualByComparingTo("500.00");
    assertThat(run2.unitValue()).isEqualByComparingTo("1.2");
    assertThat(run2.totalReturn()).as("run-relative").isEqualByComparingTo("0");
    assertThat(order(p, open.orderId()).status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(order(p, open.orderId()).statusReason()).isEqualTo(CancelReason.ACCOUNT_RESET.name());
    // a replay returns the same run; the key cannot be reused for another request
    assertThat(accountService.reset(p.id(), null, "fresh start", idem, admin()).accountId())
        .isEqualTo(run2.accountId());
    assertThatThrownBy(() -> accountService.reset(p.id(), null, "x",
        new IdempotencyRef(idem.scope(), idem.key(), "other"), admin()))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
    long newId = accountQueries.activeAccountId(p.id());
    assertThat(accountQueries.runs(p.id())).extracting(r -> r.runNo()).containsExactly(2, 1);
    assertThat(accountQueries.runs(p.id()).get(1).finalEquity()).isEqualByComparingTo("600.00");
    List<ActivityDto> acts = accountQueries.activities(newId, new ActivityFilter(Set.of(), null, null), null, 50)
        .items();
    assertThat(acts).extracting(ActivityDto::type).contains(ActivityType.RESET, ActivityType.INITIAL_FUNDING);
    assertThat(acts).filteredOn(x -> x.type() == ActivityType.RESET).singleElement()
        .satisfies(x -> assertThat(((ResetDetailDto) x.detail()).side()).isEqualTo(ResetDetailDto.ResetSide.OPENED));
  }

  @Test
  void freezingCancelsOrdersAndBlocksTradingUntilLifted() {
    Player p = human();
    String s = symbol();
    quote(s, "9.99", 300, "10.00", 300);
    OrderDto o = place(p, limit(cid(), s, Side.BUY, "1", "9.00", TimeInForce.GTC, false)).order();
    accountService.restrict(p.id(), AccountRestriction.FROZEN, CancelReason.PLAYER_FROZEN, "kill switch", admin());
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(order(p, o.orderId()).statusReason()).isEqualTo("PLAYER_FROZEN");
    assertThat(account(p).restriction()).isEqualTo(AccountRestriction.FROZEN);
    assertThat(account(p).buyingPower()).isEqualByComparingTo("0");
    assertThatThrownBy(() -> place(p, limit(cid(), s, Side.BUY, "1", "9.00", TimeInForce.GTC, false)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_RESTRICTED));
    assertThat(activities(p)).extracting(ActivityDto::type).contains(ActivityType.RESTRICTION);
    accountService.liftAdminRestriction(p.id(), admin());
    assertThat(account(p).restriction()).isEqualTo(AccountRestriction.NONE);
    assertThat(place(p, limit(cid(), s, Side.BUY, "1", "9.00", TimeInForce.GTC, false)).order().status())
        .isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void activitiesMergeFillsTransfersAndEventsNewestFirstWithACursor() {
    Player p = human();
    String s = symbol();
    buy(p, s, "1", "10.00");
    time.advance(Duration.ofSeconds(5));
    transfers.apply(single(p, TransferType.DEPOSIT, "1.00", "a"), null, admin());
    time.advance(Duration.ofSeconds(5));
    accountService.recordAdminView(p.accountId(), "support ticket", admin());
    List<ActivityDto> all = activities(p);
    assertThat(all).extracting(ActivityDto::type).containsExactly(ActivityType.ADMIN_VIEW, ActivityType.DEPOSIT,
        ActivityType.FILL, ActivityType.INITIAL_FUNDING);
    ActivityDto fill = all.get(2);
    assertThat(fill.symbol()).isEqualTo(s);
    assertThat(fill.side()).isEqualTo(Side.BUY);
    assertThat(fill.qty()).isEqualByComparingTo("1");
    assertThat(fill.price()).isEqualByComparingTo("10.00");
    assertThat(fill.orderId()).startsWith("ord_");
    assertThat(fill.executionId()).startsWith("exe_");
    var page1 = accountQueries.activities(p.accountId(), new ActivityFilter(Set.of(), null, null), null, 2);
    assertThat(page1.items()).hasSize(2);
    assertThat(page1.nextCursor()).isNotNull();
    var page2 = accountQueries.activities(p.accountId(), new ActivityFilter(Set.of(), null, null),
        com.stocks.common.web.Cursor.parse(page1.nextCursor()), 2);
    assertThat(page2.items()).extracting(ActivityDto::type)
        .containsExactly(ActivityType.FILL, ActivityType.INITIAL_FUNDING);
    assertThat(accountQueries.activities(p.accountId(), new ActivityFilter(EnumSet.of(ActivityType.FILL), null, null),
        null, 10).items()).singleElement().satisfies(x -> assertThat(x.type()).isEqualTo(ActivityType.FILL));
  }

  @Test
  void transferValidationRejectsBadRequests() {
    Player p = human();
    expect(() -> transfers.apply(single(p, TransferType.DEPOSIT, "0.00", "x"), null, admin()));
    expect(() -> transfers.apply(single(p, TransferType.DEPOSIT, "1.001", "x"), null, admin()));
    expect(() -> transfers.apply(single(p, TransferType.DEPOSIT, "1.00", " "), null, admin()));
    expect(() -> transfers.apply(single(p, TransferType.INITIAL, "1.00", "x"), null, admin()));
    assertThatThrownBy(() -> transfers.apply(new CashTransferCommand(TransferScope.SINGLE, "nobody-" + next(),
        Set.of(), TransferType.DEPOSIT, BigDecimal.ONE, null, "x", false, false), null, admin()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
  }

  // ------------------------------------------------------------------------------------------- helpers

  private void expect(Runnable r) {
    assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  private long admin() {
    return fx.admin("adm" + next()).id();
  }

  static CashTransferCommand single(Player p, TransferType type, String amount, String memo) {
    return new CashTransferCommand(TransferScope.SINGLE, p.handle(), Set.of(), type, new BigDecimal(amount), null,
        memo, false, false);
  }

  private BigDecimal units(Player p) {
    return jdbc.sql("SELECT units FROM account WHERE id = ?").param(accountQueries.activeAccountId(p.id()))
        .query(BigDecimal.class).single();
  }

  private long count(String table) {
    return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
  }

  private List<ActivityDto> activities(Player p) {
    return accountQueries.activities(accountQueries.activeAccountId(p.id()), new ActivityFilter(Set.of(), null, null),
        null, 50).items();
  }

  private void buy(Player p, String s, String qty, String ask) {
    BigDecimal a = new BigDecimal(ask);
    String bid = a.subtract(new BigDecimal("0.02")).toPlainString();
    quote(s, bid, 1000, ask, 1000);
    OrderDto o = place(p, market(cid(), s, Side.BUY, qty)).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, bid, 1000, ask, 1000);
    print(s, ask, 100);
    step();
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.FILLED);
    time.advance(Duration.ofSeconds(1));
  }

}
