package com.stocks.trading.store;

import java.math.BigDecimal;
import java.time.Instant;

/** One {@code position} row: {@code qty} = Σ open lots, {@code costBasis} = Σ their cost (I5), buy fees included. */
public record PositionRow(long accountId, String symbol, BigDecimal qty, BigDecimal costBasis, Instant openedAt,
                          Instant updatedAt) {}
