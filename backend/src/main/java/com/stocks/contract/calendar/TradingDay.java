package com.stocks.contract.calendar;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One NYSE trading day (§9.3), internal. The wire shape is {@code contract.marketdata.dto.TradingDayDto}.
 * {@code source}: {@code alpaca}, {@code bundled}, {@code override} or {@code always-open}.
 */
public record TradingDay(LocalDate tradeDate, Instant preOpenAt, Instant rthOpenAt, Instant rthCloseAt,
                         Instant postCloseAt, LocalDate settlementDate, boolean earlyClose, String source) {}
