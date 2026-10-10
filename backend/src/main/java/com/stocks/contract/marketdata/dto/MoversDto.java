package com.stocks.contract.marketdata.dto;

import com.stocks.common.domain.MoverList;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** §18.3 {@code Movers}; {@code items[].name} is filled from {@code AssetService}. */
public record MoversDto(MoverList list, Instant asOf, String source, List<MoverItemDto> items) {

  public record MoverItemDto(String symbol, String name, BigDecimal price, BigDecimal change, BigDecimal changePct,
                             Long volume) {}
}
