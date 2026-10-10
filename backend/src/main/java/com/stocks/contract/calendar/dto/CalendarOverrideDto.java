package com.stocks.contract.calendar.dto;

import java.time.Instant;
import java.time.LocalDate;

/** §21.1 {@code CalendarOverride} (wire). {@code createdBy} is the admin's handle. */
public record CalendarOverrideDto(LocalDate date, boolean closed, Instant rthCloseAt, Instant postCloseAt,
                                  String reason, String createdBy, Instant createdAt) {}
