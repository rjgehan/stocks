package com.stocks.contract.marketdata.model;

import java.math.BigDecimal;

/** One row of a movers list. */
public record Mover(String symbol, BigDecimal price, BigDecimal change, BigDecimal changePct, Long volume) {}
