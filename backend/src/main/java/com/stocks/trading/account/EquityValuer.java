package com.stocks.trading.account;

import com.stocks.common.money.Rounding;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Live equity of an account (§16.1), for unit accounting at a flow (§16.2), a reset's final equity and the Account
 * page, from WS4's {@link ValuationService} (which reads through the caller's transaction): cash +
 * Σ {@code MARKET_VALUE(qty × mark)} + ACCRUED dividend receivables, quality = the worst mark used.
 */
@Component
public class EquityValuer {

  private final ValuationService valuations;
  private final AccountStore accounts;

  public EquityValuer(ValuationService valuations, AccountStore accounts) {
    this.valuations = valuations;
    this.accounts = accounts;
  }

  public Valuation value(AccountRow acc) {
    return valuations.valueNow(acc.id());
  }

  /** {@code U = UNIT(E / units)} if units &gt; 0, else the last transfer's U (§16.2), else 1. */
  public BigDecimal unitValue(AccountRow acc, BigDecimal equity) {
    if (acc.units().signum() > 0) {
      return Rounding.UNIT.divide(equity, acc.units());
    }
    return accounts.lastTransferUnitValue(acc.id()).orElse(BigDecimal.ONE.setScale(18));
  }
}
