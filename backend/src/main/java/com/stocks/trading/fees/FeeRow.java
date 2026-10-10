package com.stocks.trading.fees;

import com.stocks.common.domain.FeeCode;
import com.stocks.common.domain.Side;
import com.stocks.common.money.Rounding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One {@code fee_schedule} row (§8.1, §13).
 *
 * @param appliesTo   {@code SELL} or {@code BOTH}
 * @param basis       {@code PRINCIPAL} (dollars) or {@code SHARES}
 * @param rounding    {@code CEIL_CENT}, {@code HALF_UP_CENT} or {@code SUBCENT_ZERO}
 * @param maxPerOrder per-order cap, or null
 * @param effectiveTo inclusive; null = open-ended
 */
public record FeeRow(FeeCode code, String appliesTo, String basis, BigDecimal rate, String rounding,
                     BigDecimal maxPerOrder, LocalDate effectiveFrom, LocalDate effectiveTo, String note) {

  public FeeRow {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(rate, "rate");
    Objects.requireNonNull(effectiveFrom, "effectiveFrom");
  }

  /** True if this row charges {@code side} on {@code tradeDate}. */
  public boolean appliesTo(Side side, LocalDate tradeDate) {
    boolean sideOk = "BOTH".equals(appliesTo) || ("SELL".equals(appliesTo) && side == Side.SELL);
    boolean dateOk = !tradeDate.isBefore(effectiveFrom) && (effectiveTo == null || !tradeDate.isAfter(effectiveTo));
    return sideOk && dateOk;
  }

  public boolean principalBasis() {
    return "PRINCIPAL".equals(basis);
  }

  /** {@code applyRounding(rate × basis)}, then the per-order cap (§13). */
  public BigDecimal charge(BigDecimal basisAmount) {
    BigDecimal rounded = policy().apply(rate.multiply(basisAmount));
    if (maxPerOrder != null && rounded.compareTo(maxPerOrder) > 0) {
      return maxPerOrder.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }
    return rounded;
  }

  Rounding policy() {
    return switch (rounding) {
      case "CEIL_CENT" -> Rounding.FEE_CEIL;
      case "HALF_UP_CENT" -> Rounding.FEE_HALF_UP;
      case "SUBCENT_ZERO" -> Rounding.FEE_SUBCENT_ZERO;
      default -> throw new IllegalStateException("Unknown fee rounding " + rounding);
    };
  }
}
