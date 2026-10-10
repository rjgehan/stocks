package com.stocks.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WireEnumTest {

  @Test
  void wireValuesAreTheSpecStrings() {
    assertThat(HistoryRange.values()).extracting(WireEnum::wireValue)
        .containsExactly("1D", "1W", "1M", "3M", "YTD", "1Y", "ALL");
    assertThat(ChartRange.values()).extracting(WireEnum::wireValue)
        .containsExactly("1D", "5D", "1M", "3M", "6M", "YTD", "1Y", "5Y", "ALL");
    assertThat(Timeframe.values()).extracting(WireEnum::wireValue)
        .containsExactly("1Min", "5Min", "15Min", "30Min", "1Hour", "1Day", "1Week", "1Month");
  }

  @Test
  void parsingIsCaseInsensitiveAndAcceptsConstantNames() {
    assertThat(Timeframe.fromWire("1min")).isEqualTo(Timeframe.MIN1);
    assertThat(Timeframe.fromWire("1DAY")).isEqualTo(Timeframe.DAY1);
    assertThat(ChartRange.fromWire("1m")).isEqualTo(ChartRange.M1);
    assertThat(ChartRange.fromWire("ytd")).isEqualTo(ChartRange.YTD);
    assertThat(HistoryRange.fromWire("w1")).isEqualTo(HistoryRange.W1);
    assertThat(HistoryRange.fromWire(null)).isNull();
    assertThatThrownBy(() -> Timeframe.fromWire("2Min"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1Min");
  }

  @Test
  void openOrderStatuses() {
    assertThat(OrderStatus.PENDING.isOpen()).isTrue();
    assertThat(OrderStatus.WORKING.isOpen()).isTrue();
    assertThat(OrderStatus.PARTIALLY_FILLED.isOpen()).isTrue();
    assertThat(OrderStatus.FILLED.isTerminal()).isTrue();
    assertThat(OrderStatus.REJECTED.isTerminal()).isTrue();
  }
}
