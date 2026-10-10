package com.stocks.trading.store;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.AccountStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** One {@code account} row (a run, §10.1). Money columns are caches proven by invariants I2–I4. */
public record AccountRow(long id, String publicId, long playerId, int runNo, AccountStatus status,
                         AccountRestriction restriction, Instant restrictionUntil, String restrictionReason,
                         BigDecimal cashBalance, BigDecimal netContributions, BigDecimal units, int unitSeries,
                         BigDecimal startingCash, Instant openedAt, Instant closedAt, String closeReason,
                         BigDecimal finalEquity, BigDecimal finalUnitValue) {

  public boolean isActive() {
    return status == AccountStatus.ACTIVE;
  }
}
