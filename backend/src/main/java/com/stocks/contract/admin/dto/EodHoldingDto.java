package com.stocks.contract.admin.dto;

import java.math.BigDecimal;

/** §21.1 {@code AdminPlayerDetail.eodHoldings[]}: previous-EOD holdings (never live, §16.7). */
public record EodHoldingDto(String symbol, BigDecimal qty, BigDecimal mark, BigDecimal marketValue) {}
