package com.stocks.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.money.Rounding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** §30 OCC option symbols: parsing, formatting, the shared symbol pattern and the ×100 money helpers. */
class OptionSymbolTest {

  @Test
  void parsesAnOccSymbolIntoItsTerms() {
    OptionSymbol.Contract c = OptionSymbol.parse("AAPL261120C00250000").orElseThrow();
    assertThat(c.underlying()).isEqualTo("AAPL");
    assertThat(c.expiration()).isEqualTo(LocalDate.of(2026, 11, 20));
    assertThat(c.right()).isEqualTo(OptionRight.CALL);
    assertThat(c.strike()).isEqualByComparingTo("250").hasScaleOf(2);
    assertThat(c.label()).isEqualTo("AAPL 20 Nov 26 $250 Call");
    OptionSymbol.Contract p = OptionSymbol.parse("SPY261016P00662500").orElseThrow();
    assertThat(p.strike()).isEqualByComparingTo("662.50");
    assertThat(p.label()).isEqualTo("SPY 16 Oct 26 $662.5 Put");
    assertThat(OptionSymbol.parse("F270115C00012500").orElseThrow().strike()).isEqualByComparingTo("12.5");
  }

  @Test
  void formatsTheSymbolBack() {
    assertThat(OptionSymbol.format("aapl", LocalDate.of(2026, 11, 20), OptionRight.CALL, new BigDecimal("250")))
        .isEqualTo("AAPL261120C00250000");
    assertThat(OptionSymbol.format("SPY", LocalDate.of(2026, 10, 16), OptionRight.PUT, new BigDecimal("662.5")))
        .isEqualTo("SPY261016P00662500");
  }

  @Test
  void onlyWellFormedContractsAreOptions() {
    assertThat(OptionSymbol.isOption("AAPL261120C00250000")).isTrue();
    assertThat(OptionSymbol.isOption("AAPL")).isFalse();
    assertThat(OptionSymbol.isOption("BTC/USD")).isFalse();
    assertThat(OptionSymbol.isOption("BRK.B")).isFalse();
    assertThat(OptionSymbol.isOption("AAPL261131C00250000")).isFalse();      // 31 November
    assertThat(OptionSymbol.isOption("AAPL261120X00250000")).isFalse();
    assertThat(OptionSymbol.isOption("AAPL261120C00000000")).isFalse();      // strike 0
    assertThat(OptionSymbol.isOption("TOOLONGX261120C00250000")).isFalse();
    assertThat(OptionSymbol.isOption(null)).isFalse();
    Pattern any = Pattern.compile(Crypto.SYMBOL_REGEX);
    assertThat(any.matcher("AAPL261120C00250000").matches()).isTrue();
    assertThat(any.matcher("AAPL").matches()).isTrue();
    assertThat(any.matcher("BTC/USD").matches()).isTrue();
  }

  @Test
  void intrinsicValueIsTheInTheMoneyAmountPerShare() {
    OptionSymbol.Contract call = OptionSymbol.parse("XYZ261016C00100000").orElseThrow();
    OptionSymbol.Contract put = OptionSymbol.parse("XYZ261016P00100000").orElseThrow();
    assertThat(call.intrinsic(new BigDecimal("104.25"))).isEqualByComparingTo("4.25");
    assertThat(call.intrinsic(new BigDecimal("99.99"))).isEqualByComparingTo("0");
    assertThat(put.intrinsic(new BigDecimal("97.10"))).isEqualByComparingTo("2.90");
    assertThat(put.intrinsic(new BigDecimal("100.00"))).isEqualByComparingTo("0");
  }

  @Test
  void dollarAmountsAreTimesOneHundredForOptionsOnly() {
    String occ = "XYZ261016C00100000";
    assertThat(OptionSymbol.multiplier(occ)).isEqualByComparingTo("100");
    assertThat(OptionSymbol.multiplier("XYZ")).isEqualByComparingTo("1");
    assertThat(Rounding.principal(occ, Side.BUY, new BigDecimal("3"), new BigDecimal("2.05")))
        .isEqualByComparingTo("615.00");
    assertThat(Rounding.principal("XYZ", Side.BUY, new BigDecimal("3"), new BigDecimal("2.05")))
        .isEqualByComparingTo("6.15");
    assertThat(Rounding.marketValue(occ, new BigDecimal("2"), new BigDecimal("1.235"))).isEqualByComparingTo("247.00");
  }
}
