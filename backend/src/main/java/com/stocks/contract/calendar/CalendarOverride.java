package com.stocks.contract.calendar;

import java.time.Instant;
import java.time.LocalDate;

/** An admin calendar override (§9.1), internal. The wire shape is {@code contract.calendar.dto.CalendarOverrideDto}. */
public record CalendarOverride(LocalDate date, boolean closed, Instant rthCloseAt, Instant postCloseAt, String reason,
                               String createdByHandle, Instant createdAt) {}
