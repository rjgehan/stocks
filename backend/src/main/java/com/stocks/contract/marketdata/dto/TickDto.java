package com.stocks.contract.marketdata.dto;

import com.stocks.common.domain.DataQuality;
import java.math.BigDecimal;
import java.time.Instant;

/** §19 {@code /topic/tick.{SYM}} payload: a projection of {@code QuoteDto} by {@code QuoteDtoMapper}. */
public record TickDto(String symbol, BigDecimal last, BigDecimal change, BigDecimal changePct,
                      TickExtendedDto extended, Instant asOf, DataQuality quality) {

  public record TickExtendedDto(BigDecimal price, BigDecimal changePct) {}
}
