package com.stocks.trading.fees;

import com.stocks.common.domain.Side;
import com.stocks.common.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Regulatory fees (§13). Commission is $0. Fees are charged <b>cumulatively per order</b>: for each applicable row,
 * {@code exact_k = rate × Σ basis} over the order's executions with the same trade date up to and including
 * execution k, {@code rounded_k = cap(round(exact_k))}, and execution k is charged {@code rounded_k − rounded_{k−1}}.
 * Many tiny partial fills therefore never over-charge (ten 100-share sells at $50 pay $1.03 of SEC fee, not $1.10).
 */
@Component
public class FeeCalculator {

  private final FeeSchedule schedule;

  public FeeCalculator(FeeSchedule schedule) {
    this.schedule = schedule;
  }

  /**
   * Fees of one execution.
   *
   * @param priorQty       Σ qty of the order's earlier executions with the same trade date
   * @param priorPrincipal Σ principal of those executions
   */
  public Fees forExecution(Side side, LocalDate tradeDate, BigDecimal priorQty, BigDecimal priorPrincipal,
                           BigDecimal qty, BigDecimal principal) {
    BigDecimal sec = Money.ZERO;
    BigDecimal taf = Money.ZERO;
    BigDecimal cat = Money.ZERO;
    for (FeeRow r : schedule.applicable(side, tradeDate)) {
      BigDecimal before = r.principalBasis() ? priorPrincipal : priorQty;
      BigDecimal after = before.add(r.principalBasis() ? principal : qty);
      BigDecimal charge = r.charge(after).subtract(r.charge(before));
      switch (r.code()) {
        case SEC -> sec = sec.add(charge);
        case TAF -> taf = taf.add(charge);
        case CAT -> cat = cat.add(charge);
      }
    }
    return new Fees(sec, taf, cat);
  }

  /** The fees of a single execution of {@code qty} for {@code principal} (previews, holds, notional sizing). */
  public Fees estimate(Side side, BigDecimal qty, BigDecimal principal, LocalDate tradeDate) {
    return forExecution(side, tradeDate, BigDecimal.ZERO, BigDecimal.ZERO, qty, principal);
  }
}
