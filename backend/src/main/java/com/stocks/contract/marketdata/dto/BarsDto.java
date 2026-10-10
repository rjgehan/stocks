package com.stocks.contract.marketdata.dto;

import com.stocks.common.domain.Timeframe;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import java.time.Instant;
import java.util.List;

/** §18.3 {@code Bars} ({@code GET /bars/{symbol}}); {@code timeframe} is the wire value ({@code "1Min"}). */
public record BarsDto(String symbol, Timeframe timeframe, AdjustmentMode adjustment, List<BarDto> bars,
                      Instant tailFrom, String source) {}
