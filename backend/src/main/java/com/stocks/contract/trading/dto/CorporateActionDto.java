package com.stocks.contract.trading.dto;

import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.domain.CorporateActionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** §21.1 {@code CorporateAction}. {@code resolvedBy} is the admin's handle. */
public record CorporateActionDto(String id, String symbol, CorporateActionType type, LocalDate exDate,
                                 LocalDate recordDate, LocalDate payableDate, BigDecimal oldRate, BigDecimal newRate,
                                 BigDecimal cashRate, String newSymbol, CorporateActionStatus status,
                                 CorporateActionOrigin origin, String detail, Instant createdAt, Instant appliedAt,
                                 String resolvedBy) {}
