package com.stocks.contract.marketdata.model;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.LiquidityTier;

/** A tradable instrument (§15.5 {@code AssetService}). */
public record Asset(String symbol, String name, String exchange, AssetType type, boolean tradable, boolean fractionable,
                    boolean shortable, boolean easyToBorrow, boolean marginable, LiquidityTier tier, boolean active) {}
