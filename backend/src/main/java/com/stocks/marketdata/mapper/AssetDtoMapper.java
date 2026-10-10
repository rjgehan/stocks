package com.stocks.marketdata.mapper;

import com.stocks.contract.marketdata.dto.AssetDto;
import com.stocks.contract.marketdata.dto.AssetSummaryDto;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Fundamentals;
import org.springframework.stereotype.Component;

/** {@code model.Asset → AssetSummaryDto / AssetDto} (§18.3); {@code logoUrl} is the same-origin logo proxy. */
@Component
public class AssetDtoMapper {

  public static AssetSummaryDto toSummary(Asset a) {
    return new AssetSummaryDto(a.symbol(), a.name(), a.exchange(), a.type(), a.tradable(), a.fractionable());
  }

  public static AssetDto toDto(Asset a, Fundamentals f) {
    return new AssetDto(a.symbol(), a.name(), a.exchange(), a.type(), a.tradable(), a.fractionable(), a.shortable(),
        a.marginable(), a.tier(), a.active() ? AssetDto.AssetStatus.ACTIVE : AssetDto.AssetStatus.INACTIVE,
        f == null ? null : f.week52High(), f == null ? null : f.week52Low(), logoUrl(a.symbol()),
        FundamentalsDtoMapper.toDto(f));
  }

  public static String logoUrl(String symbol) {
    return "/api/v1/assets/" + symbol + "/logo";
  }
}
