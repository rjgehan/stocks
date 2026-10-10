package com.stocks.contract.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

/** An eligible print for the engine ({@code drainPrints}, §12.5, §12.7). */
public record Print(BigDecimal price, long size, Instant exchangeTs, Instant observedAt, boolean consolidated) {}
