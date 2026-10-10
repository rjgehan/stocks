package com.stocks.contract.trading.dto;

import com.stocks.common.domain.ViolationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** §18.3 {@code Violation} (GFV, §10.4). */
public record ViolationDto(String id, ViolationType type, String symbol, String orderId, BigDecimal unsettledAmount,
                           LocalDate fundingSettleDate, LocalDate tradeDate, Instant occurredAt, Instant expiresAt) {}
