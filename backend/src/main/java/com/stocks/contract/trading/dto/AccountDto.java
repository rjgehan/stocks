package com.stocks.contract.trading.dto;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.AccountStatus;
import com.stocks.common.domain.AccountType;
import com.stocks.common.domain.DataQuality;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * §18.3 {@code Account}, valued live. {@code type} is always {@code CASH} in Phase 1. Totals are run-relative
 * (§16.3); returns are fractions; {@code unitValue} scale 18.
 */
public record AccountDto(String accountId, int runNo, AccountType type, AccountStatus status,
                         AccountRestriction restriction, Instant restrictionUntil, String restrictionReason,
                         BigDecimal equity, BigDecimal longMarketValue, BigDecimal dividendsReceivable,
                         AccountCashDto cash, BigDecimal openBuyHolds, BigDecimal buyingPower,
                         BigDecimal startingCash, BigDecimal netContributions, BigDecimal totalPnl,
                         BigDecimal totalReturn, BigDecimal unitValue, BigDecimal dayPnl, BigDecimal dayReturn,
                         AccountGfvDto gfv, int dayTradesLast5Days, Instant openedAt, DataQuality marksQuality,
                         Instant asOf) {

  public record AccountCashDto(BigDecimal total, BigDecimal settled, BigDecimal unsettled,
                               BigDecimal settledAvailable, BigDecimal withdrawable) {}

  public record AccountGfvDto(int active, Instant nextExpiry, int settledCashOnlyAt, int liquidationOnlyAt) {}
}
