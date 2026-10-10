package com.stocks.marketdata.mapper;

import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.dto.MoversDto;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Mover;
import com.stocks.contract.marketdata.model.Movers;
import org.springframework.stereotype.Component;

/** {@code model.Movers → MoversDto}; {@code items[].name} comes from the asset table (§18.3). */
@Component
public class MoversDtoMapper {

  private final MarketDataService marketData;

  public MoversDtoMapper(MarketDataService marketData) {
    this.marketData = marketData;
  }

  public MoversDto toDto(Movers m) {
    return new MoversDto(m.list(), m.asOf(), m.source(), m.items().stream().map(this::item).toList());
  }

  private MoversDto.MoverItemDto item(Mover m) {
    String name = marketData.asset(m.symbol()).map(Asset::name).orElse(null);
    return new MoversDto.MoverItemDto(m.symbol(), name, m.price(), m.change(), m.changePct(), m.volume());
  }
}
