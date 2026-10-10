package com.stocks.marketdata.web;

import com.stocks.common.domain.Timeframe;
import com.stocks.common.error.ApiException;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.BarRequest;
import com.stocks.contract.marketdata.BarSeries;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.dto.QuoteDto;
import com.stocks.contract.marketdata.dto.QuotesDto;
import com.stocks.contract.marketdata.dto.SnapshotDto;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.marketdata.mapper.QuoteDtoMapper;
import com.stocks.marketdata.mapper.SnapshotDtoMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §18.2 quotes and snapshots ({@code market:read}). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "quotes", description = "Quotes and snapshots (§18.2)")
public class QuoteController {

  static final int MAX_SYMBOLS = 50;

  private final MarketDataService marketData;
  private final QuoteDtoMapper quotes;
  private final TimeSource time;

  public QuoteController(MarketDataService marketData, QuoteDtoMapper quotes, TimeSource time) {
    this.marketData = marketData;
    this.quotes = quotes;
    this.time = time;
  }

  @GetMapping("/quotes")
  @Operation(summary = "Quotes for up to 50 symbols",
      description = "Unknown symbols come back with quality NONE and null prices. Quality: SIMULATED (SIM), "
          + "REALTIME_SINGLE_VENUE (IEX), REALTIME_CONSOLIDATED (SIP), SYNTHETIC (only a consolidated last), STALE, "
          + "NONE. changePct is a fraction (0.0123 = +1.23%). Errors: VALIDATION_FAILED.")
  public QuotesDto quotes(
      @Parameter(description = "Comma-separated symbols, e.g. AAPL,MSFT (1–50)") @RequestParam String symbols) {
    List<String> list = Symbols.list(symbols, "symbols", MAX_SYMBOLS);
    if (list.isEmpty()) {
      throw ApiException.validation("symbols", "at least one symbol");
    }
    return new QuotesDto(quotes.toDtos(marketData.quotes(list).values()));
  }

  @GetMapping("/snapshots/{symbol}")
  @Operation(summary = "Quote plus the current minute and daily bars and the 52-week range",
      description = "Errors: SYMBOL_NOT_FOUND.")
  public SnapshotDto snapshot(@PathVariable String symbol) {
    String s = Symbols.normalize(symbol, "symbol");
    Symbols.require(marketData, s);
    QuoteDto q = quotes.toDto(marketData.quote(s));
    Instant now = time.now();
    Bar minute = last(marketData.bars(new BarRequest(s, null, Timeframe.MIN1, now.minus(Duration.ofMinutes(5)), null,
        1, AdjustmentMode.SPLIT, true)));
    Bar daily = last(marketData.bars(new BarRequest(s, null, Timeframe.DAY1, now.minus(Duration.ofDays(10)), null, 1,
        AdjustmentMode.SPLIT, false)));
    Fundamentals f = marketData.fundamentals(s).orElse(null);
    return SnapshotDtoMapper.toDto(q, minute, daily, f);
  }

  private static Bar last(BarSeries s) {
    return s.bars().isEmpty() ? null : s.bars().get(s.bars().size() - 1);
  }
}
