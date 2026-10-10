package com.stocks.trading.corpactions;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.contract.marketdata.SplitAdjustments;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.ActorKind;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.SplitDetailDto;
import com.stocks.testkit.GoldenFiles;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Splits on the engine (§14, §26 engine scenarios): 2:1 mid-GTC (orders cancelled, quantity doubled, cost unchanged,
 * equity and unit value continuous), and 3:2, 1-for-3 and 1-for-8 on multi-lot fractional positions (I5 after each,
 * the dust in the SPLIT event), plus the non-fractionable whole-share rule with cash in lieu (T+1, realized P&L) and a
 * reverse split that turns the whole position into cash. Invariants I1–I9 are asserted after every test.
 */
class SplitScenarioTest extends CorporateActionTestSupport {

  @Autowired SplitAdjustments splitAdjustments;

  @Test
  void twoForOneMidGtcCancelsOrdersDoublesSharesAndKeepsEquityContinuous() {
    Player p = human("5000.00");
    String s = symbol();
    buy(p, s, "10", "100.00");
    OrderDto sell = place(p, limit(cid(), s, Side.SELL, "10", "150.00", TimeInForce.GTC, false)).order();
    OrderDto bid = place(p, limit(cid(), s, Side.BUY, "1", "90.00", TimeInForce.GTC, false)).order();
    print(s, "100.00", 100);
    BigDecimal equityBefore = value(p).equity();
    BigDecimal unitBefore = unitValue(p);
    String id = split(s, MON, "1", "2");
    assertThat(row(id).status()).isEqualTo(CorporateActionStatus.PENDING);

    at0345(MON);
    CorporateActionRow done = apply(id);
    assertThat(done.status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(done.appliedAt()).isEqualTo(now());
    for (OrderDto o : List.of(sell, bid)) {
      OrderDto after = order(p, o.orderId());
      assertThat(after.status()).isEqualTo(OrderStatus.CANCELLED);
      assertThat(after.statusReason()).isEqualTo("CORPORATE_ACTION");
      assertThat(after.reservedCash()).isEqualByComparingTo("0");
      assertThat(after.reservedQty()).isEqualByComparingTo("0");
    }
    var position = accountQueries.positions(p.accountId()).stream().filter(x -> x.symbol().equals(s)).findFirst()
        .orElseThrow();
    assertThat(position.qty()).isEqualByComparingTo("20");
    assertThat(position.costBasis()).isEqualByComparingTo("1000.00");
    assertThat(lots(p.accountId(), s)).singleElement().satisfies(l -> {
      assertThat((BigDecimal) l.get("QTY_REMAINING")).isEqualByComparingTo("20");
      assertThat((BigDecimal) l.get("QTY_ORIGINAL")).isEqualByComparingTo("20");
      assertThat((BigDecimal) l.get("COST_REMAINING")).isEqualByComparingTo("1000.00");
    });
    assertThat(adjustments(p.accountId())).singleElement().satisfies(a -> {
      assertThat(a.get("SYMBOL_BEFORE")).isEqualTo(s);
      assertThat((BigDecimal) a.get("QTY_BEFORE")).isEqualByComparingTo("10");
      assertThat((BigDecimal) a.get("QTY_AFTER")).isEqualByComparingTo("20");
      assertThat((BigDecimal) a.get("COST_AFTER")).isEqualByComparingTo("1000.00");
      assertThat(a.get("CIL_LEDGER_ID")).isNull();
    });
    // the SPLIT account_event in the activity history (§18.2)
    assertThat(activities(p, ActivityType.SPLIT)).singleElement().satisfies(a -> {
      assertThat(a.id()).startsWith("aev_");
      assertThat(a.symbol()).isEqualTo(s);
      assertThat(a.qty()).isEqualByComparingTo("20");
      assertThat(a.by()).isEqualTo(ActorKind.SYSTEM);
      assertThat(a.detail()).isInstanceOfSatisfying(SplitDetailDto.class, d -> {
        assertThat(d.corporateActionId()).isEqualTo(id);
        assertThat(d.qtyBefore()).isEqualByComparingTo("10");
        assertThat(d.qtyAfter()).isEqualByComparingTo("20");
        assertThat(d.costBasis()).isEqualByComparingTo("1000.00");
        assertThat(d.cashInLieu()).isEqualByComparingTo("0");
        assertThat(d.dustQty()).isEqualByComparingTo("0");
      });
    });
    // reference prices dated before the ex-date are split-adjusted from now on (WS2 price_adjustment)
    assertThat(splitAdjustments.adjust(s, new BigDecimal("100.00"), FRI, MON)).isEqualByComparingTo("50.00");
    // equity and the unit value are continuous: the first post-split print is half the price
    time.advance(java.time.Duration.ofMinutes(1));
    print(s, "50.00", 100);
    assertThat(value(p).equity()).isEqualByComparingTo(equityBefore);
    assertThat(unitValue(p)).isEqualByComparingTo(unitBefore);
    // replaying the command is a no-op
    assertThat(apply(id).status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(adjustments(p.accountId())).hasSize(1);
  }

  @Test
  void threeForTwoOneForThreeAndOneForEightOnMultiLotFractionalPositions() {
    Map<String, Object> golden = new LinkedHashMap<>();
    golden.put("3:2", fractionalCase("2", "3", "0.49999", "0.99998", "0.00001"));
    golden.put("1:3", fractionalCase("3", "1", "0.11111", "0.22222", "0.00000"));
    golden.put("1:8", fractionalCase("8", "1", "0.04166", "0.08332", "0.00001"));
    GoldenFiles.assertMatches("engine", "split-scenarios", golden);
  }

  /** Two lots of 0.33333 at $90, split old→new on Monday; returns the SPLIT event detail for the golden file. */
  private Map<String, String> fractionalCase(String oldRate, String newRate, String lotAfter, String qtyAfter,
                                             String dust) {
    time.set(com.stocks.testkit.TestTimes.START);
    Player p = human("500.00");
    String s = symbol();
    buy(p, s, "0.33333", "90.00");
    buy(p, s, "0.33333", "90.00");
    md.dayEndMark(s, FRI, "90.00");
    BigDecimal cash = value(p).cash();
    String id = split(s, MON, oldRate, newRate);
    at0345(MON);
    assertThat(apply(id).status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(lots(p.accountId(), s)).hasSize(2).allSatisfy(l ->
        assertThat((BigDecimal) l.get("QTY_REMAINING")).isEqualByComparingTo(lotAfter));
    var pos = accountQueries.positions(p.accountId()).stream().filter(x -> x.symbol().equals(s)).findFirst()
        .orElseThrow();
    assertThat(pos.qty()).isEqualByComparingTo(qtyAfter);
    assertThat(pos.costBasis()).isEqualByComparingTo("60.00");          // 2 × CEIL(0.33333 × 90) = 2 × 30.00
    assertThat(value(p).cash()).as("dust worth < 1 cent is forfeited, no ledger row").isEqualByComparingTo(cash);
    assertThat(adjustments(p.accountId())).hasSize(2);
    SplitDetailDto d = (SplitDetailDto) activities(p, ActivityType.SPLIT).getFirst().detail();
    assertThat(d.dustQty()).isEqualByComparingTo(dust);
    assertThat(d.cashInLieu()).isEqualByComparingTo("0.00");
    assertThat(invariants.check().failures()).isEmpty();               // I5 after each split
    Map<String, String> out = new LinkedHashMap<>();
    out.put("qtyBefore", d.qtyBefore().toPlainString());
    out.put("qtyAfter", d.qtyAfter().toPlainString());
    out.put("costBasis", d.costBasis().toPlainString());
    out.put("dustQty", d.dustQty().toPlainString());
    out.put("cashInLieu", d.cashInLieu().toPlainString());
    out.put("realizedPnl", d.realizedPnl().toPlainString());
    return out;
  }

  @Test
  void nonFractionableThreeForTwoPaysCashInLieuSettlingTPlusOne() {
    Player p = human("1000.00");
    String s = wholeShareSymbol();
    buy(p, s, "1", "100.00");
    md.dayEndMark(s, FRI, "90.00");                                    // the raw close of one pre-split share
    BigDecimal cashBefore = value(p).cash();
    String id = split(s, MON, "2", "3");
    at0345(MON);
    assertThat(apply(id).status()).isEqualTo(CorporateActionStatus.APPLIED);
    var pos = accountQueries.positions(p.accountId()).stream().filter(x -> x.symbol().equals(s)).findFirst()
        .orElseThrow();
    assertThat(pos.qty()).isEqualByComparingTo("1");
    assertThat(pos.costBasis()).isEqualByComparingTo("66.67");
    assertThat(value(p).cash()).isEqualByComparingTo(cashBefore.add(new BigDecimal("30.00")));
    ActivityDto cil = activities(p, ActivityType.CASH_IN_LIEU).getFirst();
    assertThat(cil.amount()).isEqualByComparingTo("30.00");
    assertThat(cil.symbol()).isEqualTo(s);
    assertThat(cil.tradeDate()).isEqualTo(MON);
    assertThat(cil.settleDate()).isAfter(MON);                           // T+1 (bank holiday aware)
    assertThat(cil.by()).isEqualTo(ActorKind.SYSTEM);
    assertThat(cil.memo()).contains("Cash in lieu").contains("-3.33");
    Long cilLedger = jdbc.sql("SELECT id FROM cash_ledger WHERE account_id = ? AND entry_type = 'CASH_IN_LIEU'")
        .param(p.accountId()).query(Long.class).single();
    assertThat(jdbc.sql("SELECT corporate_action_id FROM cash_ledger WHERE id = ?").param(cilLedger)
        .query(Long.class).single()).isEqualTo(row(id).id());
    assertThat(adjustments(p.accountId())).singleElement()
        .satisfies(a -> assertThat(((Number) a.get("CIL_LEDGER_ID")).longValue()).isEqualTo(cilLedger));
    SplitDetailDto d = (SplitDetailDto) activities(p, ActivityType.SPLIT).getFirst().detail();
    assertThat(d.cashInLieu()).isEqualByComparingTo("30.00");
    assertThat(d.realizedPnl()).isEqualByComparingTo("-3.33");
    assertThat(d.dustQty()).isEqualByComparingTo("0.5");
  }

  @Test
  void aReverseSplitBelowOneWholeShareTurnsThePositionIntoCash() {
    Player p = human("1000.00");
    String s = wholeShareSymbol();
    buy(p, s, "3", "10.00");
    buy(p, s, "2", "10.00");
    md.dayEndMark(s, FRI, "10.00");
    String id = split(s, MON, "8", "1");
    at0345(MON);
    apply(id);
    assertThat(accountQueries.positions(p.accountId())).noneMatch(x -> x.symbol().equals(s));
    assertThat(lots(p.accountId(), s)).hasSize(2).allSatisfy(l -> {
      assertThat(l.get("CLOSED_AT")).isNotNull();
      assertThat((BigDecimal) l.get("QTY_REMAINING")).isEqualByComparingTo("0");
    });
    assertThat(activities(p, ActivityType.CASH_IN_LIEU).getFirst().amount()).isEqualByComparingTo("50.00");
    SplitDetailDto d = (SplitDetailDto) activities(p, ActivityType.SPLIT).getFirst().detail();
    assertThat(d.qtyAfter()).isEqualByComparingTo("0");
    assertThat(d.realizedPnl()).isEqualByComparingTo("0.00");
  }

  @Test
  void aSplitWithoutHoldersStillRecordsThePriceAdjustment() {
    String s = symbol();
    String id = split(s, MON, "1", "4");
    at0345(MON);
    assertThat(apply(id).status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(splitAdjustments.adjust(s, new BigDecimal("40.00"), FRI, MON)).isEqualByComparingTo("10.00");
  }

  private String wholeShareSymbol() {
    String s = "W" + next();
    md.withAsset(new Asset(s, s + " Corp", "NYSE", AssetType.COMMON_STOCK, true, false, true, true, false,
        LiquidityTier.MEGA, true));
    return s;
  }
}
