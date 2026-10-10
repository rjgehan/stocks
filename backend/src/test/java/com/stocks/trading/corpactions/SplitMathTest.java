package com.stocks.trading.corpactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.config.JacksonConfig;
import com.stocks.testkit.GoldenFiles;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * §14 split arithmetic ({@link SplitMath}, pure) on the golden cases of §14 and §26: 2:1, and 3:2, 1-for-3 and
 * 1-for-8 on multi-lot fractional positions (two lots of 0.33333: under 3:2 each becomes 0.49999 and the entitlement
 * is 0.99999; under 1-for-8 each becomes 0.04166 and the entitlement is 0.08333), plus the non-fractionable
 * whole-share rules with cash in lieu. The results are pinned in {@code golden/engine/split-math.json}
 * (regenerate with {@code -Dgolden.update=true} and review the diff); the explicit assertions state the rules.
 */
class SplitMathTest {

  static final BigDecimal ONE = BigDecimal.ONE;
  static final BigDecimal TWO = new BigDecimal("2");
  static final BigDecimal THREE = new BigDecimal("3");
  static final BigDecimal EIGHT = new BigDecimal("8");

  static SplitMath.Lot lot(long id, String qty, String cost) {
    return new SplitMath.Lot(id, new BigDecimal(qty), new BigDecimal(qty), new BigDecimal(cost));
  }

  static List<SplitMath.Lot> twoThirds() {
    return List.of(lot(1, "0.33333", "33.33"), lot(2, "0.33333", "34.00"));
  }

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  @Test
  void twoForOneDoublesEveryLotAndKeepsTheCost() {
    SplitMath.Result r = SplitMath.apply(List.of(lot(1, "10", "1000.00")), ONE, TWO, true, d("100.00"));
    assertThat(r.qtyAfter()).isEqualByComparingTo("20");
    assertThat(r.costAfter()).isEqualByComparingTo("1000.00");
    assertThat(r.lots()).singleElement().satisfies(l -> {
      assertThat(l.qtyAfter()).isEqualByComparingTo("20");
      assertThat(l.qtyOriginalAfter()).isEqualByComparingTo("20");
      assertThat(l.costAfter()).isEqualByComparingTo("1000.00");
      assertThat(l.closed()).isFalse();
    });
    assertThat(r.dust()).isEqualByComparingTo("0");
    assertThat(r.cashInLieu()).isEqualByComparingTo("0.00");
    assertThat(r.realizedPnl()).isEqualByComparingTo("0.00");
  }

  @Test
  void threeForTwoOnTwoFractionalLotsLeavesOneHundredThousandthOfDust() {
    SplitMath.Result r = SplitMath.apply(twoThirds(), TWO, THREE, true, d("90.00"));
    assertThat(r.lots()).extracting(SplitMath.LotResult::qtyAfter).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("0.49999"), d("0.49999"));
    assertThat(r.entitled()).isEqualByComparingTo("0.99999");
    assertThat(r.qtyAfter()).isEqualByComparingTo("0.99998");          // Σ lots, never floored separately (I5)
    assertThat(r.dust()).isEqualByComparingTo("0.00001");
    assertThat(r.cashInLieu()).isEqualByComparingTo("0.00");           // 0.00001 × 90 × 2/3 = $0.0006
    assertThat(r.forfeitedQty()).isEqualByComparingTo("0.00001");
    assertThat(r.costAfter()).isEqualByComparingTo("67.33");           // cost unchanged
  }

  @Test
  void oneForThreeOnTwoFractionalLotsHasNoDust() {
    SplitMath.Result r = SplitMath.apply(twoThirds(), THREE, ONE, true, d("90.00"));
    assertThat(r.lots()).extracting(SplitMath.LotResult::qtyAfter).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("0.11111"), d("0.11111"));
    assertThat(r.entitled()).isEqualByComparingTo("0.22222");
    assertThat(r.dust()).isEqualByComparingTo("0");
    assertThat(r.costAfter()).isEqualByComparingTo("67.33");
  }

  @Test
  void oneForEightOnTwoFractionalLots() {
    SplitMath.Result r = SplitMath.apply(twoThirds(), EIGHT, ONE, true, d("4.00"));
    assertThat(r.lots()).extracting(SplitMath.LotResult::qtyAfter).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("0.04166"), d("0.04166"));
    assertThat(r.entitled()).isEqualByComparingTo("0.08333");
    assertThat(r.qtyAfter()).isEqualByComparingTo("0.08332");
    assertThat(r.dust()).isEqualByComparingTo("0.00001");
    assertThat(r.cashInLieu()).isEqualByComparingTo("0.00");           // 0.00001 × 4 × 8 = $0.00032
  }

  @Test
  void fractionableDustWorthACentOrMoreIsPaidOnTheNewestLot() {
    SplitMath.Result r = SplitMath.apply(twoThirds(), TWO, THREE, true, d("100000.00"));
    assertThat(r.cashInLieu()).isEqualByComparingTo("0.66");           // 0.00001 × 100000 × 2/3 = 0.6666…
    assertThat(r.forfeitedQty()).isEqualByComparingTo("0");
    assertThat(r.realizedPnl()).isEqualByComparingTo("0.66");          // no cost relieved
    assertThat(r.lots()).extracting(SplitMath.LotResult::absorbedCashInLieu).containsExactly(false, true);
  }

  @Test
  void nonFractionableThreeForTwoKeepsWholeSharesAndPaysTheHalfInCash() {
    SplitMath.Result r = SplitMath.apply(List.of(lot(1, "1", "100.00")), TWO, THREE, false, d("90.00"));
    assertThat(r.entitled()).isEqualByComparingTo("1.5");
    assertThat(r.qtyAfter()).isEqualByComparingTo("1");
    assertThat(r.cashInLieuQty()).isEqualByComparingTo("0.5");
    assertThat(r.cashInLieu()).isEqualByComparingTo("30.00");          // 0.5 × 90 × 2/3
    assertThat(r.costAfter()).isEqualByComparingTo("66.67");           // 100 × 1/1.5, HALF_EVEN
    assertThat(r.relievedCost()).isEqualByComparingTo("33.33");
    assertThat(r.realizedPnl()).isEqualByComparingTo("-3.33");
    assertThat(r.lots()).singleElement().satisfies(l -> assertThat(l.absorbedCashInLieu()).isTrue());
  }

  @Test
  void nonFractionableOneForThreeRemovesFromTheNewestLotFirst() {
    SplitMath.Result r = SplitMath.apply(List.of(lot(1, "4", "40.00"), lot(2, "4", "41.00")), THREE, ONE, false,
        d("5.00"));
    assertThat(r.entitled()).isEqualByComparingTo("2.66666");
    assertThat(r.qtyAfter()).isEqualByComparingTo("2");
    assertThat(r.lots()).extracting(SplitMath.LotResult::qtyAfter).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("1.33333"), d("0.66667"));
    assertThat(r.lots().get(0).costAfter()).isEqualByComparingTo("40.00");
    assertThat(r.lots().get(1).costAfter()).isEqualByComparingTo("20.50");
    assertThat(r.cashInLieu()).isEqualByComparingTo("9.99");           // 0.66666 × 5 × 3 = 9.9999
    assertThat(r.realizedPnl()).isEqualByComparingTo("-10.51");
    assertThat(r.lots()).extracting(SplitMath.LotResult::absorbedCashInLieu).containsExactly(false, true);
  }

  @Test
  void nonFractionableOneForEightOfFiveSharesIsAllCash() {
    SplitMath.Result r = SplitMath.apply(List.of(lot(1, "3", "30.00"), lot(2, "2", "20.00")), EIGHT, ONE, false,
        d("10.00"));
    assertThat(r.entitled()).isEqualByComparingTo("0.625");
    assertThat(r.qtyAfter()).isEqualByComparingTo("0");
    assertThat(r.lots()).allSatisfy(l -> {
      assertThat(l.closed()).isTrue();
      assertThat(l.costAfter()).isEqualByComparingTo("0.00");
    });
    assertThat(r.cashInLieu()).isEqualByComparingTo("50.00");          // 0.625 × 10 × 8
    assertThat(r.realizedPnl()).isEqualByComparingTo("0.00");
    assertThat(r.lots()).extracting(SplitMath.LotResult::absorbedCashInLieu).containsExactly(false, true);
  }

  @Test
  void aSliverLotThatRoundsToZeroIsClosedAndItsCostRealized() {
    SplitMath.Result r = SplitMath.apply(List.of(lot(1, "0.00004", "0.01"), lot(2, "1", "100.00")), EIGHT, ONE,
        true, d("80.00"));
    assertThat(r.lots().get(0).closed()).isTrue();
    assertThat(r.lots().get(0).qtyOriginalAfter()).isEqualByComparingTo("0.00001");   // qty_original > 0 (CHECK)
    assertThat(r.lots().get(1).qtyAfter()).isEqualByComparingTo("0.125");
    assertThat(r.costAfter()).isEqualByComparingTo("100.00");
    assertThat(r.realizedPnl()).isEqualByComparingTo("-0.01");
  }

  @Test
  void ratesMustBePositiveAndDifferAndCashInLieuNeedsAPrice() {
    assertThatThrownBy(() -> SplitMath.apply(twoThirds(), BigDecimal.ZERO, TWO, true, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SplitMath.apply(twoThirds(), TWO, TWO, true, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SplitMath.apply(List.of(lot(1, "1", "1.00")), TWO, THREE, false, null))
        .isInstanceOf(NullPointerException.class);
    // no cash in lieu due: no price needed
    assertThat(SplitMath.apply(List.of(lot(1, "1", "1.00")), ONE, TWO, false, null).qtyAfter())
        .isEqualByComparingTo("2");
  }

  // ------------------------------------------------------------------------------------------- applyDelta (late APPLY)

  /** Review regression: only the shares held at the ex cut-off are split; later purchases keep their quantity. */
  @Test
  void aLateForwardSplitAddsOnlyTheCutoffSharesShareOfTheSplitToTheOldestLot() {
    // 10 held at the cut-off (lot 1), 4 bought after it at the post-split price (lot 2)
    SplitMath.Result r = SplitMath.applyDelta(List.of(lot(1, "10", "1000.00"), lot(2, "4", "200.00")), d("10"), ONE,
        TWO, true, d("100.00"));
    assertThat(r.qtyBefore()).isEqualByComparingTo("14");
    assertThat(r.qtyAfter()).isEqualByComparingTo("24");
    assertThat(r.costAfter()).isEqualByComparingTo("1200.00");
    assertThat(r.lots()).extracting(SplitMath.LotResult::qtyAfter).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("20"), d("4"));
    assertThat(r.lots().get(0).qtyOriginalAfter()).isEqualByComparingTo("20");
    assertThat(r.cashInLieu()).isEqualByComparingTo("0.00");
    // sold everything since the cut-off: the extra shares are owed in cash (non-fractionable 3:2 of 3 → 1.5 more)
    SplitMath.Result sold = SplitMath.applyDelta(List.of(), d("3"), TWO, THREE, false, d("20.00"));
    assertThat(sold.qtyAfter()).isEqualByComparingTo("0");
    assertThat(sold.cashInLieuQty()).isEqualByComparingTo("1.5");
    assertThat(sold.cashInLieu()).as("1.5 × 20.00 × 2 / 3").isEqualByComparingTo("20.00");
  }

  @Test
  void aLateReverseSplitRemovesFromTheOldestLotsKeepsTheirCostAndRefusesToOverdraw() {
    // 1-for-10 on 15 held at the cut-off (lot 1); 2 bought since at the post-split price (lot 2); non-fractionable
    SplitMath.Result r = SplitMath.applyDelta(List.of(lot(1, "15", "45.00"), lot(2, "2", "60.00")), d("15"), d("10"),
        ONE, false, d("3.00"));
    // entitled 1.5 → keeps 1 and is paid 0.5 × 3.00 × 10 = 15.00; lot 1 loses 14 shares, cost kept
    assertThat(r.qtyAfter()).isEqualByComparingTo("3");
    assertThat(r.lots()).extracting(SplitMath.LotResult::qtyAfter).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("1"), d("2"));
    assertThat(r.costAfter()).isEqualByComparingTo("105.00");
    assertThat(r.cashInLieu()).isEqualByComparingTo("15.00");
    assertThat(r.lots().get(1).absorbedCashInLieu()).isTrue();
    // a lot emptied by the removal is closed and its cost realized
    SplitMath.Result emptied = SplitMath.applyDelta(List.of(lot(1, "5", "15.00"), lot(2, "10", "300.00")), d("10"),
        d("10"), ONE, true, d("3.00"));
    assertThat(emptied.lots()).extracting(SplitMath.LotResult::closed).containsExactly(true, false);
    assertThat(emptied.qtyAfter()).isEqualByComparingTo("6");
    assertThat(emptied.realizedPnl()).isEqualByComparingTo("-15.00");
    assertThatThrownBy(() -> SplitMath.applyDelta(List.of(lot(1, "2", "60.00")), d("20"), d("10"), ONE, true,
        d("3.00"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("only 2 are held now");
  }

  @Test
  void neverDividesRatesOnTheirOwn() {
    // 1-for-3 and 3:2 do not terminate as ratios; every quantity is computed multiply-first at scale 5
    for (String q : List.of("1", "7", "0.00001", "123.45678", "199999.99999")) {
      BigDecimal qty = d(q);
      assertThat(SplitMath.splitQty(qty, THREE, ONE).scale()).isEqualTo(5);
      assertThat(SplitMath.splitQty(qty, TWO, THREE).scale()).isEqualTo(5);
      assertThat(SplitMath.cashInLieu(d("0.00001"), d("33.33"), THREE, d("7")).scale()).isEqualTo(2);
    }
  }

  @Test
  void goldenFile() {
    Map<String, Object> cases = new LinkedHashMap<>();
    cases.put("2:1 one lot", SplitMath.apply(List.of(lot(1, "10", "1000.00")), ONE, TWO, true, d("100.00")));
    cases.put("3:2 two fractional lots", SplitMath.apply(twoThirds(), TWO, THREE, true, d("90.00")));
    cases.put("1:3 two fractional lots", SplitMath.apply(twoThirds(), THREE, ONE, true, d("90.00")));
    cases.put("1:8 two fractional lots", SplitMath.apply(twoThirds(), EIGHT, ONE, true, d("4.00")));
    cases.put("3:2 fractional dust paid", SplitMath.apply(twoThirds(), TWO, THREE, true, d("100000.00")));
    cases.put("3:2 non-fractionable", SplitMath.apply(List.of(lot(1, "1", "100.00")), TWO, THREE, false,
        d("90.00")));
    cases.put("1:3 non-fractionable two lots", SplitMath.apply(List.of(lot(1, "4", "40.00"), lot(2, "4", "41.00")),
        THREE, ONE, false, d("5.00")));
    cases.put("1:8 non-fractionable all cash", SplitMath.apply(List.of(lot(1, "3", "30.00"), lot(2, "2", "20.00")),
        EIGHT, ONE, false, d("10.00")));
    List<String> names = new ArrayList<>(cases.keySet());
    assertThat(names).hasSize(8);
    // through JSON text, so lot ids compare as plain JSON numbers (a long would be a LongNode in valueToTree)
    GoldenFiles.assertMatches("engine", "split-math", JacksonConfig.newMapper().writeValueAsString(cases));
  }
}
