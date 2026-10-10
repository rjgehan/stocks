package com.stocks.contract.marketdata.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** §18.3 {@code Bar}: {@code t = start}, {@code o/h/l/c}, {@code v = volume}, {@code vw = vwap}. */
public record BarDto(Instant t, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c, long v, BigDecimal vw) {}
