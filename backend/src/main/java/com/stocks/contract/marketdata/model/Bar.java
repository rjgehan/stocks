package com.stocks.contract.marketdata.model;

import com.stocks.common.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;

/** An OHLCV bar. {@code partialVolume}: IEX-only volume (the live tail, §15.5). */
public record Bar(String symbol, Timeframe tf, Instant start, BigDecimal open, BigDecimal high, BigDecimal low,
                  BigDecimal close, long volume, BigDecimal vwap, long tradeCount, boolean partialVolume) {}
