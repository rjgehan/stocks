package com.stocks.contract.marketdata.dto;

import java.math.BigDecimal;

/** §18.3 {@code Fundamentals} (no 52-week fields; those are on {@code Asset} and {@code Snapshot}). */
public record FundamentalsDto(BigDecimal marketCap, BigDecimal peTtm, BigDecimal epsTtm, BigDecimal dividendYield,
                              BigDecimal beta, BigDecimal sharesOutstanding, String industry, String website,
                              String source) {}
