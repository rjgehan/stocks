package com.stocks.contract.marketdata;

import com.stocks.common.domain.Timeframe;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import java.time.Instant;
import java.util.List;

/** Stitched bars (§15.5 {@code BarService}). {@code tailFrom}: first IEX-tail bar, null if all SIP. */
public record BarSeries(String symbol, Timeframe timeframe, AdjustmentMode adjustment, List<Bar> bars,
                        Instant tailFrom, String source) {}
