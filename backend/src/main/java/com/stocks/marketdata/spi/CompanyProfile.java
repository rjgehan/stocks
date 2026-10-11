package com.stocks.marketdata.spi;

import java.math.BigDecimal;

/**
 * A company's size and classification for the screener (§15.10): {@code sharesOutstanding} and {@code marketCap} in
 * shares and dollars (Finnhub reports millions; converted by the client), {@code industry} the provider's own label
 * (Finnhub {@code finnhubIndustry}, e.g. "Semiconductors"). Every member but {@code symbol} may be null.
 */
public record CompanyProfile(String symbol, BigDecimal sharesOutstanding, BigDecimal marketCap, String industry,
                             String source) {}
