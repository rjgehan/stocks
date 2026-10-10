package com.stocks.trading.store;

import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;

/** One {@code order_group} row (§8 V3, §11.9), with its account's public id. */
public record OrderGroupRow(long id, String publicId, long accountId, String accountPublicId, long playerId,
                            String clientOrderId, String requestHash, OrderGroupType type, String symbol,
                            BigDecimal qty, TimeInForce timeInForce, Instant createdAt, Instant updatedAt) {}
