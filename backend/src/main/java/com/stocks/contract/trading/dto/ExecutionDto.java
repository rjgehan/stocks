package com.stocks.contract.trading.dto;

import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** §18.3 {@code Execution}. {@code realizedPnl} is null for buys. */
public record ExecutionDto(String executionId, String orderId, String symbol, Side side, BigDecimal qty,
                           BigDecimal price, BigDecimal principal, FeesDto fees, BigDecimal netAmount,
                           BigDecimal realizedPnl, Liquidity liquidity, Instant executedAt, LocalDate tradeDate,
                           LocalDate settleDate, RefQuoteDto refQuote) {}
