package com.stocks.marketdata.mapper;

import com.stocks.contract.marketdata.dto.QuoteDto;
import com.stocks.contract.marketdata.dto.SnapshotDto;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Fundamentals;
import org.springframework.stereotype.Component;

/** {@code Quote} + bars + fundamentals → {@code SnapshotDto} (§18.3 {@code Snapshot = Quote & {...}}). */
@Component
public class SnapshotDtoMapper {

  public static SnapshotDto toDto(QuoteDto quote, Bar minuteBar, Bar dailyBar, Fundamentals f) {
    return SnapshotDto.of(quote, BarDtoMapper.toDto(minuteBar), BarDtoMapper.toDto(dailyBar),
        f == null ? null : f.week52High(), f == null ? null : f.week52Low(), f == null ? null : f.marketCap());
  }
}
