package com.stocks.trading.corpactions;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.NameChangeDetailDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.watchlist.WatchlistService;
import com.stocks.portfolio.HoldingsReconstructor;
import com.stocks.testkit.GoldenFiles;
import com.stocks.trading.account.AccountCommands;
import com.stocks.trading.engine.EngineCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Cash dividends and name changes on the engine (§14, §16.1): accrual on the ex-date for the holders at the ex-date's
 * pre-open (the receivable counts in equity, so the ex-date price drop is not a loss), payment on the payable date
 * (one DIVIDEND ledger row, settled at once, {@code paid_at} = its {@code posted_at}), voiding on reset, a dividend
 * found after its ex-date (holders reconstructed at the cut-off), and a NAME_CHANGE moving positions, lots, open
 * orders and watchlists to the new symbol while history keeps the old one.
 */
class DividendAndRenameScenarioTest extends CorporateActionTestSupport {

  @Autowired CorporateActionApplyJob applyJob;
  @Autowired WatchlistService watchlists;
  @Autowired HoldingsReconstructor holdings;
  @Autowired com.stocks.contract.calendar.MarketCalendar calendar;

  int pay(java.time.LocalDate date) {
    return engine.run(new EngineCommand.PayDividendsCmd(date));
  }

  @Test
  void aDividendAccruesOnTheExDateAndIsPaidOnceOnThePayableDate() {
    Player p = human("1000.00");
    String s = symbol();
    buy(p, s, "10", "50.00");
    print(s, "50.00", 100);
    String id = record(s, CorporateActionType.CASH_DIVIDEND, MON, null, null, "0.50", WED, null);
    Valuation before = value(p);
    Map<String, Object> golden = new LinkedHashMap<>();
    golden.put("BEFORE_EX", snapshot(before));

    at0345(MON);
    assertThat(apply(id).status()).isEqualTo(CorporateActionStatus.APPLIED);
    Map<String, Object> ent = jdbc.sql("SELECT eligible_qty, amount, status, payable_date, accrued_at, paid_at"
        + " FROM dividend_entitlement WHERE account_id = ?").param(p.accountId()).query().singleRow();
    assertThat((BigDecimal) ent.get("eligible_qty")).isEqualByComparingTo("10");
    assertThat((BigDecimal) ent.get("amount")).isEqualByComparingTo("5.00");
    assertThat(ent.get("status")).isEqualTo("ACCRUED");
    assertThat(account(p).dividendsReceivable()).isEqualByComparingTo("5.00");
    assertThat(jdbc.sql("SELECT dividend_ex FROM daily_mark WHERE symbol = ? AND trade_date = ?").params(s, MON)
        .query(BigDecimal.class).single()).isEqualByComparingTo("0.50");   // the benchmark's Div_D (§16.6)
    time.set(et("2026-10-12T09:31:00"));
    print(s, "49.50", 100);                                             // the ex-date price drop
    Valuation ex = value(p);
    golden.put("EX_DATE", snapshot(ex));
    assertThat(ex.dividendsReceivable()).isEqualByComparingTo("5.00");
    assertThat(ex.equity()).isEqualByComparingTo(before.equity());     // no fake loss on the ex-date
    assertThat(unitValueOf(ex)).isEqualByComparingTo(unitValueOf(before));

    time.set(et("2026-10-13T06:00:00"));
    assertThat(pay(TUE)).isZero();                                      // not payable yet
    time.set(et("2026-10-14T06:00:00"));
    assertThat(pay(WED)).isGreaterThanOrEqualTo(1);
    Valuation paid = value(p);
    golden.put("PAID", snapshot(paid));
    assertThat(paid.cash()).isEqualByComparingTo(before.cash().add(new BigDecimal("5.00")));
    assertThat(paid.dividendsReceivable()).isEqualByComparingTo("0.00");
    assertThat(paid.equity()).isEqualByComparingTo(before.equity());   // the $5.00 counts exactly once
    Map<String, Object> ledger = jdbc.sql("""
            SELECT l.amount, l.trade_date, l.settle_date, l.posted_at, l.dividend_id, l.corporate_action_id, l.symbol,
                   d.paid_at, d.status
              FROM cash_ledger l JOIN dividend_entitlement d ON d.id = l.dividend_id
             WHERE l.account_id = ? AND l.entry_type = 'DIVIDEND'""").param(p.accountId()).query().singleRow();
    assertThat((BigDecimal) ledger.get("amount")).isEqualByComparingTo("5.00");
    assertThat(ledger.get("settle_date")).isEqualTo(ledger.get("trade_date"));   // settled at once
    assertThat(ledger.get("status")).isEqualTo("PAID");
    assertThat(instant(ledger.get("paid_at"))).isEqualTo(instant(ledger.get("posted_at")));
    assertThat(((Number) ledger.get("corporate_action_id")).longValue()).isEqualTo(row(id).id());
    assertThat(accountQueries.balances(p.accountId(), WED).settledCash())
        .isEqualByComparingTo(before.cash().add(new BigDecimal("5.00")));
    ActivityDto div = activities(p, ActivityType.DIVIDEND).getFirst();
    assertThat(div.amount()).isEqualByComparingTo("5.00");
    assertThat(div.memo()).contains(s).contains("0.5");
    assertThat(pay(WED)).isZero();                                      // idempotent
    GoldenFiles.assertMatches("performance", "dividend_continuity_engine", golden);
  }

  @Test
  void aResetBeforeThePayableDateVoidsTheDividend() {
    Player p = human("1000.00");
    String s = symbol();
    buy(p, s, "4", "25.00");
    String id = record(s, CorporateActionType.CASH_DIVIDEND, MON, null, null, "0.25", WED, null);
    at0345(MON);
    apply(id);
    time.set(et("2026-10-12T10:00:00"));
    accountService.reset(p.id(), null, "fresh start",
        new IdempotencyRef(AccountCommands.RESET_SCOPE_ADMIN, "reset-" + next(), "h"), p.id());
    Map<String, Object> ent = jdbc.sql("SELECT status, voided_at FROM dividend_entitlement WHERE account_id = ?")
        .param(p.accountId()).query().singleRow();
    assertThat(ent.get("status")).isEqualTo("VOID");
    assertThat(ent.get("voided_at")).isNotNull();
    time.set(et("2026-10-14T06:00:00"));
    pay(WED);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM cash_ledger WHERE account_id = ? AND entry_type = 'DIVIDEND'")
        .param(p.accountId()).query(Integer.class).single()).isZero();
  }

  @Test
  void aDividendFoundAfterItsExDateGoesToTheHoldersAtThePreOpen() {
    Player early = human("1000.00");
    Player late = human("1000.00");
    String s = symbol();
    buy(early, s, "4", "20.00");                                         // Friday: entitled
    time.set(et("2026-10-12T10:00:00"));
    buy(late, s, "10", "20.00");                                         // ex-date morning: not entitled
    time.set(et("2026-10-12T11:00:00"));
    String id = record(s, CorporateActionType.CASH_DIVIDEND, MON, null, null, "1.00", WED, null);
    assertThat(row(id).status()).isEqualTo(CorporateActionStatus.PENDING);
    assertThat(applyJob.applyOverdue()).isZero();
    assertThat(row(id).status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(account(early).dividendsReceivable()).isEqualByComparingTo("4.00");
    assertThat(account(late).dividendsReceivable()).isEqualByComparingTo("0.00");
  }

  @Test
  void theApplyJobRunsADaysActionsInOrderAndCatchesUp() {
    Player p = human("1000.00");
    String s = symbol();
    buy(p, s, "2", "10.00");
    String div = record(s, CorporateActionType.CASH_DIVIDEND, MON, null, null, "0.10", WED, null);
    String split = split(s, MON, "1", "2");
    assertThat(applyJob.occurrencesBetween(et("2026-10-09T20:00:00"), et("2026-10-13T20:00:00"),
        calendar))
        .extracting(o -> o.runKey()).containsExactly("2026-10-12", "2026-10-13");
    at0345(MON);
    applyJob.run(MON.toString());
    assertThat(row(split).status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(row(div).status()).isEqualTo(CorporateActionStatus.APPLIED);
    // the split ran before the dividend: the dividend is paid on the post-split 4 shares
    assertThat(jdbc.sql("SELECT eligible_qty FROM dividend_entitlement WHERE account_id = ?").param(p.accountId())
        .query(BigDecimal.class).single()).isEqualByComparingTo("4");
  }

  @Test
  void aNameChangeMovesPositionsLotsAndWatchlistsAndCancelsOpenOrders() {
    Player p = human("1000.00");
    String oldSym = symbol();
    String newSym = "N" + next();
    buy(p, oldSym, "3", "20.00");
    OrderDto gtc = place(p, limit(cid(), oldSym, Side.SELL, "1", "30.00", TimeInForce.GTC, false)).order();
    watchlists.create(p.id(), "Renames " + next(), List.of(oldSym));
    BigDecimal cost = accountQueries.positions(p.accountId()).stream().filter(x -> x.symbol().equals(oldSym))
        .findFirst().orElseThrow().costBasis();
    String id = record(oldSym, CorporateActionType.NAME_CHANGE, MON, null, null, null, null, newSym);
    Instant friClose = et("2026-10-09T16:00:00");

    at0345(MON);
    assertThat(apply(id).status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(order(p, gtc.orderId()).status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(accountQueries.positions(p.accountId())).noneMatch(x -> x.symbol().equals(oldSym));
    md.withAsset(newSym);
    md.print(newSym, now(), "20.00", 100);
    var pos = accountQueries.positions(p.accountId()).stream().filter(x -> x.symbol().equals(newSym)).findFirst()
        .orElseThrow();
    assertThat(pos.qty()).isEqualByComparingTo("3");
    assertThat(pos.costBasis()).isEqualByComparingTo(cost);
    assertThat(lots(p.accountId(), newSym)).hasSize(1);
    assertThat(lots(p.accountId(), oldSym)).isEmpty();
    assertThat(adjustments(p.accountId())).singleElement().satisfies(a -> {
      assertThat(a.get("symbol_before")).isEqualTo(oldSym);
      assertThat(a.get("symbol_after")).isEqualTo(newSym);
      assertThat((BigDecimal) a.get("qty_before")).isEqualByComparingTo("3");
      assertThat((BigDecimal) a.get("qty_after")).isEqualByComparingTo("3");
    });
    assertThat(activities(p, ActivityType.NAME_CHANGE)).singleElement().satisfies(a -> {
      assertThat(a.symbol()).isEqualTo(newSym);
      assertThat(a.detail()).isInstanceOfSatisfying(NameChangeDetailDto.class, d -> {
        assertThat(d.oldSymbol()).isEqualTo(oldSym);
        assertThat(d.newSymbol()).isEqualTo(newSym);
        assertThat(d.qty()).isEqualByComparingTo("3");
      });
    });
    // history keeps the old symbol: the Friday fill, and the holdings replay before and after the rename
    assertThat(activities(p, ActivityType.FILL)).allSatisfy(a -> assertThat(a.symbol()).isEqualTo(oldSym));
    assertThat(holdings.holdingsAt(p.accountId(), friClose)).containsOnlyKeys(oldSym);
    assertThat(holdings.holdingsAt(p.accountId(), now())).containsOnlyKeys(newSym);
    assertThat(watchlists.list(p.id()).stream().flatMap(w -> w.symbols().stream()).toList())
        .contains(newSym).doesNotContain(oldSym);
    // the new symbol's asset row was created from the old one (trade_order references asset)
    assertThat(jdbc.sql("SELECT fractionable FROM asset WHERE symbol = ?").param(newSym).query(Boolean.class)
        .single()).isTrue();
  }

  private Map<String, String> snapshot(Valuation v) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("cash", v.cash().toPlainString());
    m.put("longMarketValue", v.longMarketValue().toPlainString());
    m.put("receivable", v.dividendsReceivable().toPlainString());
    m.put("equity", v.equity().toPlainString());
    return m;
  }

  private static BigDecimal unitValueOf(Valuation v) {
    return v.equity().divide(v.units(), 10, java.math.RoundingMode.HALF_EVEN);
  }

  private static Instant instant(Object jdbcValue) {
    if (jdbcValue instanceof java.time.OffsetDateTime o) {
      return o.toInstant();
    }
    if (jdbcValue instanceof java.sql.Timestamp t) {
      return t.toInstant();
    }
    return Instant.parse(jdbcValue.toString());
  }
}
