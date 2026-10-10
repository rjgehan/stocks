package com.stocks.contract.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

/** Run-relative day and total P&L (§16.2, §16.3); the same anchors as the TODAY board. Returns are fractions. */
public record AccountPnl(BigDecimal dayPnl, BigDecimal dayReturn, BigDecimal totalPnl, BigDecimal totalReturn,
                         BigDecimal unitValue, Instant asOf) {}
