package com.stocks.marketdata.mapper;

import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.dto.TradingDayDto;
import org.springframework.stereotype.Component;

/** {@code calendar.TradingDay → TradingDayDto} ({@code tradeDate → date}), §18.3. */
@Component
public class TradingDayDtoMapper {

  public static TradingDayDto toDto(TradingDay d) {
    return new TradingDayDto(d.tradeDate(), d.preOpenAt(), d.rthOpenAt(), d.rthCloseAt(), d.postCloseAt(),
        d.settlementDate(), d.earlyClose());
  }
}
