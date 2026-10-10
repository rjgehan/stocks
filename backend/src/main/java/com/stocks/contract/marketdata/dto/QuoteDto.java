package com.stocks.contract.marketdata.dto;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * §18.3 {@code Quote}: {@code /quotes}, {@code /market/indices}, {@code /topic/quote.*}, MCP {@code get_quotes}.
 * {@code ageMs = now − asOf} (null if {@code asOf} is null); {@code session} = the current session.
 */
public record QuoteDto(String symbol, BigDecimal bid, Long bidSize, BigDecimal ask, Long askSize, BigDecimal last,
                       Long lastSize, Instant lastAt, BigDecimal prevClose, BigDecimal change, BigDecimal changePct,
                       BigDecimal dayOpen, BigDecimal dayHigh, BigDecimal dayLow, Long volume, boolean volumeIsPartial,
                       ExtendedLineDto extended, Instant asOf, Long ageMs, DataQuality quality, String source,
                       boolean halted, SessionType session) {}
