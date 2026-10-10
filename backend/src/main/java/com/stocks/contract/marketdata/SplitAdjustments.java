package com.stocks.contract.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Split adjustment of reference prices (§14; table {@code price_adjustment}, WS2). */
public interface SplitAdjustments {

  /** Called by the engine inside {@code ApplyCorporateActionCmd}'s transaction. */
  void record(String symbol, LocalDate exDate, BigDecimal oldRate, BigDecimal newRate, long corporateActionId);

  /** {@code price × Π(old_rate) ÷ Π(new_rate)} over splits with {@code priceDate < ex_date ≤ asOfDate}; {@code Rounding.SPLIT_ADJUST}. */
  BigDecimal adjust(String symbol, BigDecimal price, LocalDate priceDate, LocalDate asOfDate);
}
