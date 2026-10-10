package com.stocks.contract.calendar;

import com.stocks.common.domain.SessionType;
import java.time.Instant;
import java.time.LocalDate;

/** The session at an instant (§9.3). {@code tradingDate} is null on non-trading days. */
public record SessionState(SessionType type, LocalDate tradingDate, Instant startedAt, Instant endsAt,
                           SessionType nextType, Instant nextStartsAt) {}
