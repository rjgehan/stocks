package com.stocks.contract.marketdata.dto;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.LiquidityTier;
import java.math.BigDecimal;

/** §18.3 {@code Asset = AssetSummary & {...}}, flattened (the first six components are {@code AssetSummary}'s). */
public record AssetDto(String symbol, String name, String exchange, AssetType type, boolean tradable,
                       boolean fractionable,
                       boolean shortable, boolean marginable, LiquidityTier liquidityTier, AssetStatus status,
                       BigDecimal week52High, BigDecimal week52Low, String logoUrl, FundamentalsDto fundamentals) {

  public enum AssetStatus { ACTIVE, INACTIVE }
}
