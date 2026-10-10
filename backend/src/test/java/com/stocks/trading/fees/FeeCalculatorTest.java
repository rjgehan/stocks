package com.stocks.trading.fees;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.FeeCode;
import com.stocks.common.domain.Side;
import com.stocks.common.money.Rounding;
import com.stocks.testkit.TestDb;
import java.math.BigDecimal;
import java.time.LocalDate;
import com.stocks.trading.testkit.FeeRows;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** §13 golden table (required tests) plus the cumulative-charging property. */
class FeeCalculatorTest {

  static final LocalDate OCT_9 = LocalDate.of(2026, 10, 9);
  static final LocalDate JAN_4_2027 = LocalDate.of(2027, 1, 4);

  final FeeCalculator fees = FeeRows.calculator();

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  @Test
  void sell1000SharesAt50OnOctober9() {
    Fees f = fees.estimate(Side.SELL, d("1000"), d("50000.00"), OCT_9);
    assertThat(f.sec()).isEqualByComparingTo("1.03");
    assertThat(f.taf()).isEqualByComparingTo("0.00");
    assertThat(f.cat()).isEqualByComparingTo("0.00");
    assertThat(f.total()).isEqualByComparingTo("1.03");
  }

  @Test
  void sellFiveDollarsNotional() {
    // §13 golden: $5.00 at a bid of 227.41 → qty floor5(5.00 / 227.41) = 0.02198; principal FLOOR(4.99847) = 4.99
    BigDecimal qty = Rounding.NOTIONAL_QTY.divide(d("5.00"), d("227.41"));
    BigDecimal principal = Rounding.principal(Side.SELL, qty, d("227.41"));
    assertThat(qty).isEqualByComparingTo("0.02198");
    assertThat(principal).isEqualByComparingTo("4.99");
    Fees f = fees.estimate(Side.SELL, qty, principal, OCT_9);
    assertThat(f.sec()).isEqualByComparingTo("0.01");
    assertThat(f.cat()).isEqualByComparingTo("0.00");
    assertThat(principal.subtract(f.total())).isEqualByComparingTo("4.98");
  }

  @Test
  void tenPartialSellsAreChargedCumulatively() {
    // cumulative exact SEC = 0.103·k → CEIL_CENT 0.11, 0.21, 0.31, 0.42, 0.52, 0.62, 0.73, 0.83, 0.93, 1.03
    String[] expected = {"0.11", "0.10", "0.10", "0.11", "0.10", "0.10", "0.11", "0.10", "0.10", "0.10"};
    BigDecimal qty = BigDecimal.ZERO;
    BigDecimal principal = BigDecimal.ZERO;
    BigDecimal total = BigDecimal.ZERO;
    for (int k = 0; k < 10; k++) {
      Fees f = fees.forExecution(Side.SELL, OCT_9, qty, principal, d("100"), d("5000.00"));
      assertThat(f.sec()).as("SEC charge of fill %d", k + 1).isEqualByComparingTo(expected[k]);
      total = total.add(f.sec());
      qty = qty.add(d("100"));
      principal = principal.add(d("5000.00"));
    }
    assertThat(total).as("not 10 × 0.11 = 1.10").isEqualByComparingTo("1.03");
  }

  @Test
  void sell100kSharesIn2027CapsTaf() {
    Fees f = fees.estimate(Side.SELL, d("100000"), d("1000000.00"), JAN_4_2027);
    assertThat(f.sec()).isEqualByComparingTo("20.60");
    assertThat(f.taf()).isEqualByComparingTo("9.79");
    assertThat(f.cat()).isEqualByComparingTo("0.30");
  }

  @Test
  void buy4000SharesPaysOneCentOfCat() {
    Fees f = fees.estimate(Side.BUY, d("4000"), d("40000.00"), OCT_9);
    assertThat(f.cat()).isEqualByComparingTo("0.01");
    assertThat(f.sec()).isEqualByComparingTo("0.00");
    assertThat(f.total()).isEqualByComparingTo("0.01");
    assertThat(f.toDto().total()).isEqualByComparingTo("0.01");
  }

  @Test
  void buysPayNoSecFeeAndSmallBuysPayNothing() {
    assertThat(fees.estimate(Side.BUY, d("10"), d("2274.10"), OCT_9).total()).isEqualByComparingTo("0.00");
    assertThat(Fees.ZERO.plus(new Fees(d("0.01"), d("0.02"), d("0.03"))).total()).isEqualByComparingTo("0.06");
  }

  @Test
  void theScheduleLoadsTheSeedRowsFromTheDatabase() {
    FeeSchedule schedule = new FeeSchedule(TestDb.fresh().jdbc());
    assertThat(schedule.all()).hasSize(5);
    assertThat(schedule.applicable(Side.SELL, OCT_9)).extracting(FeeRow::code)
        .containsExactly(FeeCode.SEC, FeeCode.TAF, FeeCode.CAT);
    assertThat(schedule.applicable(Side.BUY, OCT_9)).extracting(FeeRow::code).containsExactly(FeeCode.CAT);
    assertThat(new FeeCalculator(schedule).estimate(Side.SELL, d("1000"), d("50000.00"), OCT_9).total())
        .isEqualByComparingTo("1.03");
  }

  /** The §13 golden table as a file ({@code golden/fees/section-13.json}, WS3); the tests above pin each row. */
  @Test
  void theSection13TableMatchesItsGoldenFile() {
    java.util.Map<String, Object> table = new java.util.LinkedHashMap<>();
    table.put("sell1000At50On20261009", fees.estimate(Side.SELL, d("1000"), d("50000.00"), OCT_9).toDto());
    table.put("sell5DollarsNotionalOn20261009", fees.estimate(Side.SELL, d("0.02198"), d("5.00"), OCT_9).toDto());
    java.util.List<Object> partials = new java.util.ArrayList<>();
    BigDecimal qty = BigDecimal.ZERO;
    BigDecimal principal = BigDecimal.ZERO;
    for (int k = 0; k < 10; k++) {
      partials.add(fees.forExecution(Side.SELL, OCT_9, qty, principal, d("100"), d("5000.00")).toDto());
      qty = qty.add(d("100"));
      principal = principal.add(d("5000.00"));
    }
    table.put("tenPartialSellsOf100At50On20261009", partials);
    table.put("sell100000At10On20270104", fees.estimate(Side.SELL, d("100000"), d("1000000.00"), JAN_4_2027).toDto());
    table.put("buy4000At10On20261009", fees.estimate(Side.BUY, d("4000"), d("40000.00"), OCT_9).toDto());
    com.stocks.testkit.GoldenFiles.assertMatches("fees", "section-13", table);
  }

  /** Fee deltas sum to the rounded cumulative fee, whatever the split into partial fills (§26 property). */
  @Property(tries = 200)
  void deltasSumToTheRoundedCumulativeFee(@ForAll @IntRange(min = 1, max = 20) int fills,
                                          @ForAll @IntRange(min = 1, max = 5000) int sharesPerFill,
                                          @ForAll @IntRange(min = 1, max = 50000) int centsPrice) {
    BigDecimal price = BigDecimal.valueOf(centsPrice).movePointLeft(2);
    BigDecimal qty = BigDecimal.ZERO;
    BigDecimal principal = BigDecimal.ZERO;
    BigDecimal secSum = BigDecimal.ZERO;
    BigDecimal catSum = BigDecimal.ZERO;
    for (int k = 0; k < fills; k++) {
      BigDecimal q = BigDecimal.valueOf(sharesPerFill);
      BigDecimal p = Rounding.PRINCIPAL_SELL.multiply(q, price);
      Fees f = fees.forExecution(Side.SELL, JAN_4_2027, qty, principal, q, p);
      assertThat(f.sec().signum()).isGreaterThanOrEqualTo(0);
      secSum = secSum.add(f.sec());
      catSum = catSum.add(f.cat());
      qty = qty.add(q);
      principal = principal.add(p);
    }
    Fees once = fees.estimate(Side.SELL, qty, principal, JAN_4_2027);
    assertThat(secSum).isEqualByComparingTo(once.sec());
    assertThat(catSum).isEqualByComparingTo(once.cat());
  }
}
