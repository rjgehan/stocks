package com.stocks.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

  private static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  @Test
  void cashIsNormalizedToScale2() {
    assertThat(Money.parse("amount", "500")).isEqualTo(d("500.00"));
    assertThat(Money.parse("amount", " 500.1 ")).isEqualTo(d("500.10"));
    assertThat(Money.parse("amount", "1.230")).isEqualTo(d("1.23"));   // trailing zero is not a 3rd decimal
    assertThat(Money.require("amount", d("-0.5"))).isEqualTo(d("-0.50"));
    assertThat(Money.ZERO.scale()).isEqualTo(2);
  }

  @Test
  void moreThanTwoDecimalsIsAValidationErrorNotARounding() {
    assertThatThrownBy(() -> Money.parse("amount", "10.005"))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
          assertThat(e.fields()).singleElement().satisfies(f -> assertThat(f.field()).isEqualTo("amount"));
        });
    assertThatThrownBy(() -> Money.parse("amount", "abc")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Money.parse("amount", "")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Money.require("amount", null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Money.parse("amount", "1e30")).isInstanceOf(ApiException.class);
  }

  @Test
  void sumIsNullSafe() {
    assertThat(Money.sum(d("1.10"), null, d("2.205"))).isEqualByComparingTo("3.305");
    assertThat(Money.sum()).isEqualTo(d("0.00"));
  }

  @Test
  void ticks() {
    assertThat(Prices.tick(d("1.00"))).isEqualTo(d("0.01"));
    assertThat(Prices.tick(d("0.9999"))).isEqualTo(d("0.0001"));
    assertThat(Prices.isOnTick(d("227.41"))).isTrue();
    assertThat(Prices.isOnTick(d("227.415"))).isFalse();
    assertThat(Prices.isOnTick(d("0.1234"))).isTrue();
    assertThat(Prices.isOnTick(d("0.12345"))).isFalse();
    assertThat(Prices.requireOnTick("limitPrice", d("227.4"))).isEqualTo(d("227.4000"));
    assertThatThrownBy(() -> Prices.requireOnTick("limitPrice", d("227.415")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_TICK));
    assertThatThrownBy(() -> Prices.requireOnTick("stopPrice", d("1.00001")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_TICK));
  }

  @Test
  void quantities() {
    assertThat(Qty.isValid(d("0.00001"))).isTrue();
    assertThat(Qty.isValid(d("200000"))).isTrue();
    assertThat(Qty.isValid(d("200000.00001"))).isFalse();
    assertThat(Qty.isValid(d("0"))).isFalse();
    assertThat(Qty.isValid(d("-1"))).isFalse();
    assertThat(Qty.isValid(d("0.000001"))).isFalse();
    assertThat(Qty.isValid(null)).isFalse();
    assertThat(Qty.isWhole(d("10.00000"))).isTrue();
    assertThat(Qty.isWhole(d("10.5"))).isFalse();
    assertThat(Qty.normalize(d("1.5"))).isEqualTo(d("1.50000"));
    assertThatThrownBy(() -> Qty.normalize(d("1.000001"))).isInstanceOf(ArithmeticException.class);
  }
}
