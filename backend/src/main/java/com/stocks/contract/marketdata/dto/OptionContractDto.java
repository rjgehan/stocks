package com.stocks.contract.marketdata.dto;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.OptionRight;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * §30 one contract of an option chain ({@code GET /options/{underlying}/chain}, MCP {@code get_option_chain}). Prices
 * are per share (a contract is 100 shares); sizes, volume and open interest are contracts. {@code mid} is the mark:
 * the bid/ask mid, the bid without an ask. {@code impliedVolatility} and {@code delta} come only from feeds that
 * publish them (null on the indicative feed).
 */
public record OptionContractDto(String symbol, String underlying, LocalDate expiration, OptionRight right,
                                BigDecimal strike, BigDecimal bid, Long bidSize, BigDecimal ask, Long askSize,
                                BigDecimal mid, BigDecimal last, Long volume, Long openInterest,
                                BigDecimal impliedVolatility, BigDecimal delta, Instant quoteAt, DataQuality quality) {}
