package com.stocks.trading.account;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.money.Money;
import com.stocks.contract.trading.Balances;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cash-account balances (§10.2), a pure function of ledger aggregates and open buy holds. Settlement needs no job: a
 * ledger row counts as settled once {@code settle_date ≤ asOfEt}.
 * <pre>
 * unsettledCash    = cashTotal − settledCash               (may be &lt; 0: purchases not yet settled)
 * settledAvailable = cashTotal − unsettledCredits          (= settledCash − unsettledDebits)
 * buyingPower      = NONE → cashTotal − holds;  SETTLED_CASH_ONLY → max(0, max(0, settledAvailable) − holds);
 *                    LIQUIDATION_ONLY, FROZEN → 0
 * withdrawable     = max(0, min(settledAvailable, cashTotal − holds))
 * </pre>
 */
public final class BalanceCalculator {

  /** Ledger sums for one account as of an ET date. */
  public record LedgerSums(BigDecimal cashTotal, BigDecimal settledCash, BigDecimal unsettledCredits,
                           BigDecimal unsettledDebits) {}

  private BalanceCalculator() {}

  public static Balances compute(LocalDate asOfEt, LedgerSums s, BigDecimal openBuyHolds,
                                 AccountRestriction restriction) {
    BigDecimal cash = scale(s.cashTotal());
    BigDecimal settled = scale(s.settledCash());
    BigDecimal credits = scale(s.unsettledCredits());
    BigDecimal debits = scale(s.unsettledDebits());
    BigDecimal holds = scale(openBuyHolds);
    BigDecimal settledAvailable = cash.subtract(credits);
    BigDecimal free = cash.subtract(holds);
    BigDecimal bp = switch (restriction) {
      case NONE -> free;
      case SETTLED_CASH_ONLY -> settledAvailable.max(Money.ZERO).subtract(holds).max(Money.ZERO);
      case LIQUIDATION_ONLY, FROZEN -> Money.ZERO;
    };
    BigDecimal withdrawable = settledAvailable.min(free).max(Money.ZERO);
    return new Balances(asOfEt, cash, settled, cash.subtract(settled), credits, debits, settledAvailable, holds, bp,
        withdrawable, restriction);
  }

  private static BigDecimal scale(BigDecimal v) {
    return v == null ? Money.ZERO : v.setScale(Money.SCALE, java.math.RoundingMode.UNNECESSARY);
  }
}
