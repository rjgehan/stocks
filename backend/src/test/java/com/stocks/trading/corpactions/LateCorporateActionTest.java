package com.stocks.trading.corpactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.trading.CorporateActionQueries;
import com.stocks.contract.trading.CorporateActionResolution;
import com.stocks.contract.trading.CorporateActionService;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.portfolio.EodSnapshotJob;
import com.stocks.testkit.Fixtures;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Review regressions (§14): corporate actions found or applied after their ex-date, admin resolutions, and the
 * continuity of the EOD equity series while a split waits for the admin. The test week: Fri 10-09, Mon 10-12 (ex),
 * Tue 10-13, Wed 10-14.
 */
class LateCorporateActionTest extends CorporateActionTestSupport {

  @Autowired EodSnapshotJob eod;
  @Autowired CorporateActionService service;
  @Autowired CorporateActionQueries queries;
  @Autowired CorporateActionApplyJob applyJob;
  @Autowired PayDividendsJob payJob;

  // ------------------------------------------------------------------------------------------- EOD continuity

  /** While a split waits for the admin, the EOD values the pre-split quantity at the pre-ex-date day-end mark. */
  @Test
  void aHeldSplitKeepsItsPreSplitValueInTheExDateEodSnapshot() {
    Player p = human("3000.00");
    String s = symbol();
    buy(p, s, "10", "100.00");
    md.dayEndMark(s, FRI, "100.00").dayEndMark("SPY", FRI, "500.00").dayEndMark("SPY", MON, "500.00");
    time.set(et("2026-10-09T20:20:00"));
    eod.runFor(FRI);

    // Mon 04:01 SplitGuard: a 2-for-1 nobody reported; the admin has not resolved it by Monday's EOD
    time.set(et("2026-10-12T04:01:00"));
    service.flagDetectedSplit(s, MON, BigDecimal.ONE, new BigDecimal("2"));
    assertThat(queries.symbolsOnHold()).contains(s);
    assertThat(queries.holds()).containsEntry(s, MON);
    md.dayEndMark(s, MON, "50.00");                       // the market traded the split shares all day
    time.set(et("2026-10-12T20:20:00"));
    eod.runFor(MON);
    assertThat(equity(p, MON)).as("Monday EOD while the split is on hold").isEqualByComparingTo(equity(p, FRI));
    assertThat(holding(p, MON, s)).isEqualTo("10.00000 x 100.0000");

    // resolved on Tuesday: Tuesday's EOD has the split shares at the post-split mark
    time.set(et("2026-10-13T09:00:00"));
    service.resolve(row(flagged(s)).publicId(), CorporateActionResolution.APPLY, BigDecimal.ONE,
        new BigDecimal("2"), "confirmed", admin());
    md.dayEndMark(s, TUE, "50.00").dayEndMark("SPY", TUE, "500.00");
    time.set(et("2026-10-13T20:20:00"));
    eod.runFor(TUE);
    assertThat(equity(p, TUE)).isEqualByComparingTo(equity(p, FRI));
    assertThat(holding(p, TUE, s)).isEqualTo("20.00000 x 50.0000");
  }

  /** A split applied in catch-up after its ex-date (server down over it): the ex-date's EOD runs after it. */
  @Test
  void aSplitAppliedLateInCatchUpKeepsTheExDateEodContinuous() {
    Player p = human("3000.00");
    String s = symbol();
    buy(p, s, "10", "100.00");
    String split = split(s, MON, "1", "2");               // known in advance: PENDING
    md.dayEndMark(s, FRI, "100.00").dayEndMark(s, MON, "50.00")
        .dayEndMark("SPY", FRI, "500.00").dayEndMark("SPY", MON, "500.00");
    time.set(et("2026-10-09T20:20:00"));
    eod.runFor(FRI);

    // down from Saturday to Tuesday 10:00; catch-up runs Monday 03:45 (apply), then Monday 20:20 (EOD), in order
    time.set(et("2026-10-13T10:00:00"));
    assertThat(apply(split).status()).isEqualTo(CorporateActionStatus.APPLIED);
    eod.runFor(MON);
    assertThat(equity(p, MON)).as("Monday EOD after a late split").isEqualByComparingTo(equity(p, FRI));
    assertThat(holding(p, MON, s)).isEqualTo("20.00000 x 50.0000");
    Instant effective = jdbc.sql("SELECT effective_at FROM position_adjustment WHERE account_id = ?")
        .param(p.accountId()).query((rs, n) -> Jdbc.instant(rs, "effective_at")).single();
    assertThat(effective).as("dated at the nominal 03:45 of the ex-date").isEqualTo(et("2026-10-12T03:45:00"));
  }

  /** The same split + dividend on one ex-date pay the same dividend whether applied at 03:45 or in catch-up. */
  @Test
  void aSplitAndADividendAppliedInCatchUpPayTheSameDividendAsOnTime() {
    Player p = human("1000.00");
    String s = symbol();
    buy(p, s, "2", "10.00");
    String div = record(s, CorporateActionType.CASH_DIVIDEND, MON, null, null, "0.10", WED, null);
    String split = split(s, MON, "1", "2");
    time.set(et("2026-10-12T10:00:00"));                  // the server was down at 03:45; catch-up at boot
    applyJob.run(MON.toString());
    assertThat(row(split).status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(row(div).status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(jdbc.sql("SELECT eligible_qty FROM dividend_entitlement WHERE account_id = ?").param(p.accountId())
        .query(BigDecimal.class).single()).as("on time (03:45) it is 4").isEqualByComparingTo("4");
  }

  // ------------------------------------------------------------------------------------------- late splits

  /**
   * A split first reported after its ex-date, on a symbol nobody held at the ex cut-off: shares bought since were
   * bought at post-split prices, so it only adjusts the price history; there is no hold and nothing is split.
   */
  @Test
  void aLateSplitIsNeverAppliedToSharesBoughtAfterItsExDate() {
    Player p = human("3000.00");
    String s = symbol();
    time.set(et("2026-10-13T10:00:00"));                  // Tuesday: the split (ex Monday) already trades
    buy(p, s, "10", "50.00");
    OrderDto gtc = place(p, limit(cid(), s, Side.SELL, "5", "55.00", TimeInForce.GTC, false)).order();
    BigDecimal before = value(p).equity();

    time.set(et("2026-10-14T02:30:00"));                  // Wednesday's sync sees the split for the first time
    String late = split(s, MON, "1", "2");
    assertThat(row(late).status()).as("left for the engine to decide").isEqualTo(CorporateActionStatus.PENDING);
    applyJob.applyOverdue();                              // what the sync job runs right after its upsert
    CorporateActionRow r = row(late);
    assertThat(r.status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(r.detail()).contains("nobody held");
    assertThat(queries.symbolsOnHold()).doesNotContain(s);
    assertThat(position(p, s).qty()).as("bought after the ex-date at the post-split price").isEqualByComparingTo("10");
    assertThat(order(p, gtc.orderId()).status()).as("entered after the ex-date, priced post-split")
        .isNotEqualTo(OrderStatus.CANCELLED);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM price_adjustment WHERE symbol = ?").param(s).query(Integer.class)
        .single()).isEqualTo(1);
    assertThat(value(p).equity()).isEqualByComparingTo(before);
  }

  /**
   * A split found late when its holders have traded since goes to the admin; APPLY then splits only the shares each
   * account held at the ex cut-off, so shares bought after the ex-date at post-split prices are never split.
   */
  @Test
  void anAdminApplyAfterTradesSplitsOnlyTheSharesHeldAtTheExCutoff() {
    Player early = human("3000.00");
    Player late = human("3000.00");
    String s = symbol();
    buy(early, s, "10", "100.00");                        // Friday, pre-split
    time.set(et("2026-10-12T10:00:00"));                  // Monday, the ex-date: the market trades the split shares
    buy(early, s, "4", "50.00");
    buy(late, s, "6", "50.00");

    time.set(et("2026-10-14T02:30:00"));                  // Wednesday's sync sees the split for the first time
    String split = split(s, MON, "1", "2");
    applyJob.applyOverdue();
    assertThat(row(split).status()).isEqualTo(CorporateActionStatus.NEEDS_ADMIN);
    assertThat(row(split).detail()).contains("held at the ex cut-off");
    assertThat(queries.symbolsOnHold()).contains(s);

    service.resolve(split, CorporateActionResolution.APPLY, BigDecimal.ONE, new BigDecimal("2"), "confirmed",
        admin());
    assertThat(row(split).status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(position(early, s).qty()).as("10 held at the cut-off became 20, plus the 4 bought on the ex-date")
        .isEqualByComparingTo("24");
    assertThat(position(early, s).costBasis()).as("cost unchanged").isEqualByComparingTo("1200.00");
    assertThat(position(late, s).qty()).as("bought only after the ex-date").isEqualByComparingTo("6");
    assertThat(adjustments(late.accountId())).isEmpty();
    assertThat(lots(early.accountId(), s)).extracting(l -> ((BigDecimal) l.get("qty_remaining")).stripTrailingZeros()
        .toPlainString()).containsExactly("20", "4");
  }

  /** The admin cannot apply a reverse split that would remove more shares than an account holds now. */
  @Test
  void aReverseSplitThatWouldRemoveMoreThanIsHeldIsRefused() {
    Player p = human("3000.00");
    String s = symbol();
    buy(p, s, "20", "3.00");                              // Friday: 20 pre-split shares
    time.set(et("2026-10-12T10:00:00"));                  // Monday (1-for-10 ex-date): sells 18 at $30
    quote(s, "29.98", 100_000, "30.00", 100_000);
    OrderDto sell = place(p, market(cid(), s, Side.SELL, "18")).order();
    time.advance(java.time.Duration.ofSeconds(1));
    quote(s, "29.98", 100_000, "30.00", 100_000);
    step();
    assertThat(order(p, sell.orderId()).status()).isEqualTo(OrderStatus.FILLED);
    time.set(et("2026-10-14T02:30:00"));
    String split = split(s, MON, "10", "1");
    applyJob.applyOverdue();
    assertThat(row(split).status()).isEqualTo(CorporateActionStatus.NEEDS_ADMIN);
    long adminId = admin();
    assertThatThrownBy(() -> service.resolve(split, CorporateActionResolution.APPLY, BigDecimal.TEN, BigDecimal.ONE,
        "confirmed", adminId)).isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCode.CORPORATE_ACTION_NOT_RESOLVABLE);
          assertThat(e.getMessage()).contains("only 2 are held now");
        });
    assertThat(position(p, s).qty()).isEqualByComparingTo("2");
    assertThat(row(split).status()).isEqualTo(CorporateActionStatus.NEEDS_ADMIN);
  }

  // ------------------------------------------------------------------------------------------- renames

  /** A renamed reverse split (Alpaca new_symbol) prices its cash in lieu at the raw close of one OLD share. */
  @Test
  void cashInLieuOfARenamedReverseSplitIsPricedAtThePreSplitClose() {
    Player p = human("3000.00");
    String oldSym = "W" + next();
    String newSym = "N" + next();
    md.withAsset(nonFractionable(oldSym));
    md.withAsset(nonFractionable(newSym));
    buy(p, oldSym, "15", "3.00");
    md.dayEndMark(oldSym, FRI, "3.00");
    String name = record(oldSym, CorporateActionType.NAME_CHANGE, MON, null, null, null, null, newSym);
    String split = record(newSym, CorporateActionType.REVERSE_SPLIT, MON, "10", "1", null, null, null);
    at0345(MON);
    apply(name);
    CorporateActionRow r = apply(split);
    assertThat(r.status()).as(String.valueOf(r.detail())).isEqualTo(CorporateActionStatus.APPLIED);
    BigDecimal cil = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM cash_ledger WHERE account_id = ?"
        + " AND entry_type = 'CASH_IN_LIEU'").param(p.accountId()).query(BigDecimal.class).single();
    assertThat(cil).as("0.5 new share = 5 old shares x $3.00").isEqualByComparingTo("15.00");
    assertThat(position(p, newSym).qty()).isEqualByComparingTo("1");
  }

  /** Without a pre-ex-date price, a post-split price is converted back to one old share, never used as is. */
  @Test
  void aPostSplitPriceIsConvertedBackForTheCashInLieu() {
    Player p = human("3000.00");
    String s = "V" + next();
    md.withAsset(nonFractionable(s));
    buy(p, s, "3", "20.00");                              // Friday: 3 shares, no day-end mark recorded
    String split = split(s, MON, "2", "3");               // 3-for-2: 4.5 shares → 4 + cash for 0.5
    time.set(et("2026-10-12T04:01:00"));
    service.flagDetectedSplit(s, MON, new BigDecimal("2"), new BigDecimal("3"));
    time.set(et("2026-10-12T10:00:00"));
    print(s, "13.50", 100);                               // the post-split market price
    service.resolve(split, CorporateActionResolution.APPLY, new BigDecimal("2"), new BigDecimal("3"), "ok", admin());
    BigDecimal cil = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM cash_ledger WHERE account_id = ?"
        + " AND entry_type = 'CASH_IN_LIEU'").param(p.accountId()).query(BigDecimal.class).single();
    // one old share = 13.50 × 3 / 2 = 20.25; 0.5 new share = 0.5 × 20.25 × 2 / 3 = 6.75
    assertThat(cil).isEqualByComparingTo("6.75");
  }

  /** A NAME_CHANGE at 03:45: until the new symbol has a price, the old symbol's prices keep its market value. */
  @Test
  void aRenamedPositionKeepsItsMarketValueBeforeTheNewSymbolsFirstPrint() {
    Player p = human("3000.00");
    String oldSym = symbol();
    String newSym = "R" + next();
    buy(p, oldSym, "10", "40.00");
    print(oldSym, "60.00", 100);                          // Friday: it rallies to $60
    md.dayEndMark(oldSym, FRI, "60.00");
    String name = record(oldSym, CorporateActionType.NAME_CHANGE, MON, null, null, null, null, newSym);
    time.set(et("2026-10-12T03:44:00"));
    BigDecimal before = value(p).equity();
    at0345(MON);
    apply(name);
    md.withAsset(newSym);                                 // the asset sync knows the new symbol
    time.set(et("2026-10-12T03:50:00"));
    assertThat(value(p).equity()).as("a rename is not a gain or a loss").isEqualByComparingTo(before);
  }

  // ------------------------------------------------------------------------------------------- never early

  /** A "Run now" with a future run key never applies an action before its ex-date or pays before its date. */
  @Test
  void aFutureRunKeyNeverAppliesOrPaysEarly() {
    Player p = human("1000.00");
    String s = symbol();
    time.set(et("2026-10-12T10:00:00"));                  // Monday
    buy(p, s, "4", "10.00");
    String split = split(s, WED, "1", "2");
    String div = record(s, CorporateActionType.CASH_DIVIDEND, TUE, null, null, "0.25", WED, null);
    time.set(et("2026-10-13T10:00:00"));                  // Tuesday: the dividend's ex-date has come, the split's not
    applyJob.run(WED.toString());
    assertThat(row(split).status()).as("ex-date tomorrow").isEqualTo(CorporateActionStatus.PENDING);
    assertThat(row(div).status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(position(p, s).qty()).isEqualByComparingTo("4");
    payJob.run(WED.toString());
    assertThat(jdbc.sql("SELECT status FROM dividend_entitlement WHERE account_id = ?").param(p.accountId())
        .query(String.class).single()).as("payable tomorrow").isEqualTo("ACCRUED");
  }

  /** An admin APPLY before the ex-date confirms the rates; the split then applies at 03:45 on its ex-date. */
  @Test
  void anAdminApplyBeforeTheExDateWaitsForIt() {
    Player p = human("1000.00");
    String s = symbol();
    buy(p, s, "4", "10.00");
    String bad = record(s, CorporateActionType.FORWARD_SPLIT, WED, null, "2", null, null, null);
    assertThat(row(bad).status()).isEqualTo(CorporateActionStatus.NEEDS_ADMIN);
    service.resolve(bad, CorporateActionResolution.APPLY, BigDecimal.ONE, new BigDecimal("2"), "rates", admin());
    assertThat(row(bad).status()).isEqualTo(CorporateActionStatus.PENDING);
    assertThat(position(p, s).qty()).isEqualByComparingTo("4");
    at0345(WED);
    applyJob.run(WED.toString());
    assertThat(row(bad).status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(position(p, s).qty()).isEqualByComparingTo("8");
  }

  // ------------------------------------------------------------------------------------------- helpers

  private String flagged(String symbol) {
    return store.splitsOn(symbol, MON).getFirst().publicId();
  }

  private static Asset nonFractionable(String symbol) {
    return new Asset(symbol, symbol + " Corp", "NYSE", AssetType.COMMON_STOCK, true, false, true, true, false,
        LiquidityTier.MEGA, true);
  }

  private PositionDto position(Player p, String symbol) {
    return accountQueries.positions(p.accountId()).stream().filter(x -> x.symbol().equals(symbol)).findFirst()
        .orElseThrow();
  }

  private long admin() {
    Fixtures.PlayerRef a = fx.human("adm" + next());
    jdbc.sql("UPDATE player SET role = 'ADMIN' WHERE id = ?").param(a.id()).update();
    return a.id();
  }

  private BigDecimal equity(Player p, LocalDate d) {
    return jdbc.sql("SELECT equity FROM equity_snapshot WHERE account_id = ? AND trade_date = ?")
        .params(p.accountId(), d).query(BigDecimal.class).single();
  }

  private String holding(Player p, LocalDate d, String s) {
    return jdbc.sql("SELECT qty, mark FROM snapshot_holding WHERE account_id = ? AND trade_date = ? AND symbol = ?")
        .params(p.accountId(), d, s)
        .query((rs, n) -> rs.getBigDecimal(1).setScale(5) + " x " + rs.getBigDecimal(2).setScale(4)).optional()
        .orElse("none");
  }
}
