package com.stocks.marketdata.web;

import com.stocks.common.domain.ChartRange;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.error.ApiException;
import com.stocks.contract.marketdata.BarRequest;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.dto.BarsDto;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.marketdata.mapper.BarDtoMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §18.2 chart bars ({@code market:read}). */
@RestController
@RequestMapping("/api/v1/bars")
@Tag(name = "bars", description = "Chart bars (§18.2, §15.5)")
public class BarsController {

  static final int MAX_LIMIT = 1000;

  private final MarketDataService marketData;

  public BarsController(MarketDataService marketData) {
    this.marketData = marketData;
  }

  @GetMapping("/{symbol}")
  @Operation(summary = "Bars for a chart range or an explicit window",
      description = "Either range (1D 5D 1M 3M 6M YTD 1Y 5Y ALL, with its default timeframe: 1Min 5Min 30Min 1Day "
          + "1Day 1Day 1Day 1Week 1Month) or timeframe with start/end/limit (≤ 1000, the most recent bars). "
          + "Live data stitches SIP history older than 16 minutes with an IEX tail (tailFrom = the first tail bar, "
          + "whose volume is partial). Default adjustment SPLIT; extendedHours default true. When neither range nor "
          + "timeframe is given, range=1D. Errors: SYMBOL_NOT_FOUND, VALIDATION_FAILED.")
  public BarsDto bars(
      @PathVariable String symbol,
      @Parameter(description = "Chart range") @RequestParam(required = false) ChartRange range,
      @Parameter(description = "Bar timeframe (overrides the range default)") @RequestParam(required = false)
      Timeframe timeframe,
      @Parameter(description = "Window start (ISO-8601 instant)") @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
      @Parameter(description = "Window end (ISO-8601 instant; default now)") @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
      @Parameter(description = "1–1000") @RequestParam(required = false) Integer limit,
      @Parameter(description = "RAW, SPLIT (default) or ALL") @RequestParam(defaultValue = "SPLIT")
      AdjustmentMode adjustment,
      @Parameter(description = "Include pre- and post-market bars (default true)") @RequestParam(defaultValue = "true")
      boolean extendedHours) {
    String s = Symbols.normalize(symbol, "symbol");
    Symbols.require(marketData, s);
    if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
      throw ApiException.validation("limit", "limit must be 1.." + MAX_LIMIT);
    }
    if (range != null && start != null) {
      throw ApiException.validation("start", "give either range or start/end, not both");
    }
    if (start != null && end != null && !start.isBefore(end)) {
      throw ApiException.validation("start", "start must be before end");
    }
    ChartRange r = range == null && timeframe == null ? ChartRange.D1 : range;
    return BarDtoMapper.toDto(marketData.bars(new BarRequest(s, r, timeframe, start, end, limit, adjustment,
        extendedHours)));
  }
}
