package com.stocks.contract.marketdata.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code GET /market/indicators} and MCP {@code get_indicators}: exact daily indicators of one stock, ETF or crypto
 * pair, so a strategy compares real numbers instead of estimating them from bars. Daily closes are split and dividend
 * adjusted; when the live price is newer than the last daily bar it counts as today's close ({@code todayIncluded}).
 * A value is null when there is not enough history for it.
 */
public record IndicatorsDto(
    String symbol,
    Instant asOf,
    @Schema(description = "The live price used as today's close (the last close when there is none)") BigDecimal price,
    @Schema(description = "Whether the live price counts as today's close") boolean todayIncluded,
    @Schema(description = "Daily closes the indicators are computed from, today's included") int closes,
    BigDecimal sma5,
    BigDecimal sma20,
    BigDecimal sma50,
    BigDecimal sma100,
    BigDecimal sma200,
    @Schema(description = "price / sma200 - 1 (0.021 = 2.1% above)") BigDecimal vsSma200,
    @Schema(description = "price / sma50 - 1") BigDecimal vsSma50,
    @Schema(description = "RSI(2), Wilder smoothing, 0-100") BigDecimal rsi2,
    @Schema(description = "RSI(14), Wilder smoothing, 0-100") BigDecimal rsi14,
    @Schema(description = "price / close 21 sessions ago - 1 (about 1 month)") BigDecimal return21,
    @Schema(description = "price / close 63 sessions ago - 1 (about 3 months)") BigDecimal return63,
    @Schema(description = "price / close 126 sessions ago - 1 (about 6 months)") BigDecimal return126,
    @Schema(description = "Highest daily close of the last 252 sessions") BigDecimal high252,
    @Schema(description = "Lowest daily close of the last 252 sessions") BigDecimal low252) {}
