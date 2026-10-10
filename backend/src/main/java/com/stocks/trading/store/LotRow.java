package com.stocks.trading.store;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One open {@code position_lot} (FIFO tax lot with its GFV funding attribution, §10.4). */
public record LotRow(long id, long accountId, String symbol, long openExecutionId, LocalDate tradeDate,
                     Instant openedAt, BigDecimal qtyOriginal, BigDecimal qtyRemaining, BigDecimal costRemaining,
                     BigDecimal unsettledFunding, LocalDate fundingSettleDate) {}
