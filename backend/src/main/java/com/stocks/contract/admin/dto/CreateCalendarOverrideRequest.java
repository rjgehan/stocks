package com.stocks.contract.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;

/** {@code POST /admin/calendar/overrides} (§21.1) → {@code CalendarAdmin.putOverride}. */
public record CreateCalendarOverrideRequest(@NotNull LocalDate date, @NotNull Boolean closed, Instant rthCloseAt,
                                            Instant postCloseAt, @NotBlank String reason) {}
