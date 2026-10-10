package com.stocks.contract.marketdata.dto;

import com.stocks.common.domain.AssetType;

/** §18.3 {@code AssetSummary} ({@code GET /assets/search}, MCP {@code search_symbols}). */
public record AssetSummaryDto(String symbol, String name, String exchange, AssetType type, boolean tradable,
                              boolean fractionable) {}
