package com.stocks.contract.marketdata;

import com.stocks.common.domain.SessionType;
import java.math.BigDecimal;
import java.time.Instant;

/** The extended-hours line under a quote (Webull style). {@code session} is PRE or POST only. */
public record ExtendedLine(SessionType session, BigDecimal price, BigDecimal change, BigDecimal changePct,
                           Instant at) {}
