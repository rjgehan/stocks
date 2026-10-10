package com.stocks.contract.marketdata.dto;

import com.stocks.common.domain.SessionType;
import java.math.BigDecimal;
import java.time.Instant;

/** §18.3 {@code ExtendedLine}; {@code session} is PRE or POST only. */
public record ExtendedLineDto(SessionType session, BigDecimal price, BigDecimal change, BigDecimal changePct,
                              Instant at) {}
