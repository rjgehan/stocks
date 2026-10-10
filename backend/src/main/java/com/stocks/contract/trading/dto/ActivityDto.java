package com.stocks.contract.trading.dto;

import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * §18.3 {@code Activity}: a {@code cash_ledger} row ({@code id} {@code led_…}) or an {@code account_event} row
 * ({@code aev_…}), merged newest first (§18.2). {@code detail} is the event payload; null for ledger rows.
 */
public record ActivityDto(String id, Instant at, ActivityType type, String symbol, Side side, BigDecimal qty,
                          BigDecimal price, FeesDto fees, BigDecimal amount, BigDecimal balanceAfter,
                          LocalDate tradeDate, LocalDate settleDate, String memo, ActorKind by, String orderId,
                          String executionId, String transferId, ActivityDetailDto detail) {}
