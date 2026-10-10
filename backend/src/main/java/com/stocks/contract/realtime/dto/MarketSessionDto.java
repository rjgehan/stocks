package com.stocks.contract.realtime.dto;

import com.stocks.common.domain.SessionType;
import java.time.Instant;
import java.time.LocalDate;

/** §19 {@code /topic/market.session} payload: on change and every 60 s. */
public record MarketSessionDto(SessionType session, LocalDate tradingDate, Instant endsAt, SessionType nextSession,
                               Instant nextSessionAt, boolean earlyClose) {}
