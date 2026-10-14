package com.stocks.contract.portfolio.dto;

import com.stocks.common.domain.HistoryRange;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** §18.3 {@code PublicHistory}: unit values, plus equity (always, public mode §16.7). */
public record PublicHistoryDto(HistoryRange range, List<PublicHistoryPointDto> points) {

  public record PublicHistoryPointDto(Instant t, BigDecimal unitValue, BigDecimal equity) {}
}
