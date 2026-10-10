package com.stocks.contract.marketdata;

import com.stocks.common.domain.ChartRange;
import com.stocks.common.domain.Timeframe;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import java.time.Instant;

/** {@code GET /bars}: either a chart range (with its default timeframe, §15.5) or an explicit window. */
public record BarRequest(String symbol, ChartRange rangeOrNull, Timeframe timeframeOrNull, Instant startOrNull,
                         Instant endOrNull, Integer limitOrNull, AdjustmentMode adjustment, boolean extendedHours) {}
