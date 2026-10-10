package com.stocks.contract.marketdata.model;

import java.math.BigDecimal;

/** Finnhub {@code profile2} + {@code metric} (§15.5). The 52-week fields move to {@code AssetDto}/{@code SnapshotDto}. */
public record Fundamentals(String symbol, BigDecimal marketCap, BigDecimal peTtm, BigDecimal epsTtm,
                           BigDecimal dividendYield, BigDecimal week52High, BigDecimal week52Low, BigDecimal beta,
                           BigDecimal sharesOutstanding, String industry, String website, String source) {}
