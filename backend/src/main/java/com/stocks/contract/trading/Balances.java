package com.stocks.contract.trading;

import com.stocks.common.domain.AccountRestriction;
import java.math.BigDecimal;
import java.time.LocalDate;

/** §10.2 balances, from the ledger and open orders only (no marks). All amounts scale 2. */
public record Balances(LocalDate asOfEt, BigDecimal cashTotal, BigDecimal settledCash, BigDecimal unsettledCash,
    BigDecimal unsettledCredits, BigDecimal unsettledDebits, BigDecimal settledAvailable, BigDecimal openBuyHolds,
    BigDecimal buyingPower, BigDecimal withdrawable, AccountRestriction restriction) {}
