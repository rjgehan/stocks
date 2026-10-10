package com.stocks.trading.testkit;

import com.stocks.common.domain.FeeCode;
import com.stocks.trading.fees.FeeCalculator;
import com.stocks.trading.fees.FeeRow;
import com.stocks.trading.fees.FeeSchedule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The §8.1 {@code fee_schedule} seed rows, for tests that need fees without a database. */
public final class FeeRows {

  public static final List<FeeRow> SEED = List.of(
      new FeeRow(FeeCode.SEC, "SELL", "PRINCIPAL", new BigDecimal("0.000020600000"), "CEIL_CENT", null,
          LocalDate.of(2026, 4, 4), null, "SEC"),
      new FeeRow(FeeCode.TAF, "SELL", "SHARES", new BigDecimal("0.000195000000"), "HALF_UP_CENT",
          new BigDecimal("9.79"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30), "TAF 2026"),
      new FeeRow(FeeCode.TAF, "SELL", "SHARES", new BigDecimal("0.000000000000"), "HALF_UP_CENT", null,
          LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31), "TAF paused"),
      new FeeRow(FeeCode.TAF, "SELL", "SHARES", new BigDecimal("0.000195000000"), "HALF_UP_CENT",
          new BigDecimal("9.79"), LocalDate.of(2027, 1, 1), null, "TAF 2027"),
      new FeeRow(FeeCode.CAT, "BOTH", "SHARES", new BigDecimal("0.000003000000"), "SUBCENT_ZERO", null,
          LocalDate.of(2026, 1, 1), null, "CAT"));

  private FeeRows() {}

  public static FeeCalculator calculator() {
    return new FeeCalculator(FeeSchedule.of(SEED));
  }
}
