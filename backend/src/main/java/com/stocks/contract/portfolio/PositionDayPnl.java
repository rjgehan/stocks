package com.stocks.contract.portfolio;

import java.math.BigDecimal;

/** §16.2 position day P&L. {@code dayReturn} is null when there is no previous-EOD market value. */
public record PositionDayPnl(String symbol, BigDecimal dayPnl, BigDecimal dayReturn) {}
