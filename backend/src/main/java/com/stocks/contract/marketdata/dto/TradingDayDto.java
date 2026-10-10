package com.stocks.contract.marketdata.dto;

import java.time.Instant;
import java.time.LocalDate;

/** §18.3 {@code TradingDay} (wire; {@code contract.calendar.TradingDay.tradeDate} → {@code date}). */
public record TradingDayDto(LocalDate date, Instant preOpenAt, Instant rthOpenAt, Instant rthCloseAt,
                            Instant postCloseAt, LocalDate settlementDate, boolean earlyClose) {}
