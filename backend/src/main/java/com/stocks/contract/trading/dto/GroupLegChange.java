package com.stocks.contract.trading.dto;

import java.math.BigDecimal;

/** §18.3 {@code GroupLegChange} (Phase 2, §11.9): new prices for one leg of a group replace; null = unchanged. */
public record GroupLegChange(BigDecimal limitPrice, BigDecimal stopPrice) {}
