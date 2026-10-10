package com.stocks.marketdata.mapper;

import com.stocks.contract.marketdata.BarSeries;
import com.stocks.contract.marketdata.dto.BarDto;
import com.stocks.contract.marketdata.dto.BarsDto;
import com.stocks.contract.marketdata.model.Bar;
import org.springframework.stereotype.Component;

/** {@code model.Bar → BarDto} ({@code t = start}, {@code o/h/l/c}, {@code v = volume}, {@code vw = vwap}), §18.3. */
@Component
public class BarDtoMapper {

  public static BarDto toDto(Bar b) {
    return b == null ? null : new BarDto(b.start(), b.open(), b.high(), b.low(), b.close(), b.volume(), b.vwap());
  }

  public static BarsDto toDto(BarSeries s) {
    return new BarsDto(s.symbol(), s.timeframe(), s.adjustment(), s.bars().stream().map(BarDtoMapper::toDto).toList(),
        s.tailFrom(), s.source());
  }
}
