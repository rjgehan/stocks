package com.stocks.contract.marketdata.model;

import com.stocks.common.domain.OptionRight;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One contract of an option chain (§30): the OCC symbol and its terms, the provider's latest quote and trade, the
 * day's volume, open interest and, when the feed has them, implied volatility and delta. Quote sizes are contracts.
 */
public record OptionQuote(String symbol, String underlying, LocalDate expiration, OptionRight right, BigDecimal strike,
                          Quote quote, Trade lastTrade, Long volume, Long openInterest, BigDecimal impliedVolatility,
                          BigDecimal delta) {}
