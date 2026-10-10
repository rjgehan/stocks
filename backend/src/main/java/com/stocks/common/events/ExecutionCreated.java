package com.stocks.common.events;

import com.stocks.common.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;

/** A fill was posted (§5.3, §12.8). Ids are public ids; {@code netAmount} is the signed ledger amount. */
public record ExecutionCreated(long accountId, long playerId, String executionId, String orderId, String symbol,
                               Side side, BigDecimal qty, BigDecimal price, BigDecimal netAmount, Instant at) {}
