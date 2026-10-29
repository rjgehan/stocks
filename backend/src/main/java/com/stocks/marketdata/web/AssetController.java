package com.stocks.marketdata.web;

import com.stocks.common.error.ApiException;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.dto.AssetDto;
import com.stocks.contract.marketdata.dto.AssetSummaryDto;
import com.stocks.contract.marketdata.dto.IndicatorsDto;
import com.stocks.contract.marketdata.dto.RecommendationsDto;
import com.stocks.contract.marketdata.dto.TimeAndSalesDto;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.marketdata.config.MarketDataRuntime;
import com.stocks.marketdata.core.IndicatorService;
import com.stocks.marketdata.core.LogoService;
import com.stocks.marketdata.mapper.AssetDtoMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.2 asset endpoints: symbol search, asset detail with fundamentals and the logo proxy; Phase 2 (§15.10) Time &amp;
 * Sales and analyst recommendation trends ({@code market:read}).
 */
@RestController
@RequestMapping("/api/v1/assets")
@Tag(name = "assets", description = "Symbols, asset detail and logos (§18.2)")
public class AssetController {

  static final int MAX_SEARCH = 20;
  static final int MAX_TRADES = 200;

  private final MarketDataService marketData;
  private final MarketDataRuntime runtime;
  private final TimeSource time;
  private final IndicatorService indicators;

  public AssetController(MarketDataService marketData, MarketDataRuntime runtime, TimeSource time,
                         IndicatorService indicators) {
    this.marketData = marketData;
    this.runtime = runtime;
    this.time = time;
    this.indicators = indicators;
  }

  @GetMapping("/search")
  @Operation(summary = "Search symbols",
      description = "Ranked: exact symbol, symbol prefix, name word prefix, substring. Up to 20 results.")
  public List<AssetSummaryDto> search(
      @Parameter(description = "Symbol or company name fragment") @RequestParam(defaultValue = "") String q,
      @Parameter(description = "1–20, default 10") @RequestParam(defaultValue = "10") int limit) {
    if (limit < 1 || limit > MAX_SEARCH) {
      throw ApiException.validation("limit", "limit must be 1.." + MAX_SEARCH);
    }
    if (q.length() > 64) {
      throw ApiException.validation("q", "at most 64 characters");
    }
    return marketData.search(q, limit).stream().map(AssetDtoMapper::toSummary).toList();
  }

  @GetMapping("/{symbol}")
  @Operation(summary = "Asset detail",
      description = "Tradability, fractionability, liquidity tier, 52-week range and fundamentals. Errors: "
          + "SYMBOL_NOT_FOUND.")
  public AssetDto asset(@PathVariable String symbol) {
    String s = Symbols.normalize(symbol, "symbol");
    Asset a = Symbols.require(marketData, s);
    Fundamentals f = marketData.fundamentals(s).orElse(null);
    return AssetDtoMapper.toDto(a, f, marketData.onCorporateActionHold(s));
  }

  @GetMapping("/{symbol}/trades")
  @Operation(summary = "Time & Sales (recent prints)",
      description = "Phase 2 (§15.10): the symbol's most recent prints, newest first, each with its side against the "
          + "quote in force when it arrived (AT_ASK, AT_BID, BETWEEN, UNKNOWN). Only prints a trade stream delivered: "
          + "source SIP (consolidated), IEX (single venue, a few percent of the volume), SIMULATED, or NONE when no "
          + "stream covers the symbol right now (streaming=false; the quote page's /topic/trades.{SYM} subscription "
          + "asks for one). Prices are raw (as traded). Errors: SYMBOL_NOT_FOUND, VALIDATION_FAILED.")
  public TimeAndSalesDto trades(@PathVariable String symbol,
      @Parameter(description = "1–200, default 60") @RequestParam(defaultValue = "60") int limit) {
    if (limit < 1 || limit > MAX_TRADES) {
      throw ApiException.validation("limit", "limit must be 1.." + MAX_TRADES);
    }
    String s = Symbols.normalize(symbol, "symbol");
    Symbols.require(marketData, s);
    return TimeAndSalesDto.of(marketData.timeAndSales(s, limit), time.now());
  }

  @GetMapping("/{symbol}/indicators")
  @Operation(summary = "Exact daily indicators",
      description = "Moving averages (5, 20, 50, 100, 200 days), price vs the 50- and 200-day averages, RSI(2) and "
          + "RSI(14) (Wilder), 1-, 3- and 6-month returns and the 52-week range, from split and dividend adjusted "
          + "daily closes with the live price as today's close (todayIncluded). Null where history is too short. "
          + "Stocks, ETFs and crypto pairs (BTC-USD in the path). Errors: SYMBOL_NOT_FOUND.")
  public IndicatorsDto indicators(@PathVariable String symbol) {
    String s = Symbols.normalize(symbol, "symbol");
    Symbols.require(marketData, s);
    return indicators.indicators(s);
  }

  @GetMapping("/{symbol}/recommendations")
  @Operation(summary = "Analyst recommendation trends",
      description = "Phase 2 (§15.10): monthly counts of strong buy, buy, hold, sell and strong sell ratings, newest "
          + "first (Finnhub's free recommendation trends, cached 12 h). periods is empty and source is \"none\" when "
          + "no source covers the symbol (no Finnhub token, an ETF). Errors: SYMBOL_NOT_FOUND.")
  public RecommendationsDto recommendations(@PathVariable String symbol) {
    String s = Symbols.normalize(symbol, "symbol");
    Symbols.require(marketData, s);
    return RecommendationsDto.of(s, marketData.recommendations(s));
  }

  @GetMapping(value = "/{symbol}/logo", produces = MediaType.IMAGE_PNG_VALUE)
  @Operation(summary = "Company logo",
      description = "A PNG (a generated placeholder for an unknown symbol or when no logo is available). A real "
          + "logo is cached for a year by the browser, a placeholder for an hour.")
  @ApiResponse(responseCode = "200", description = "PNG image",
      content = @Content(mediaType = MediaType.IMAGE_PNG_VALUE, schema = @Schema(type = "string", format = "binary")))
  public ResponseEntity<byte[]> logo(@PathVariable String symbol) {
    String s = Symbols.normalize(symbol, "symbol");
    // only symbols in the universe reach the provider: made-up symbols must not spend the shared Alpaca budget
    LogoService.Logo logo = marketData.asset(s).isPresent() ? runtime.logos().logo(s)
        : new LogoService.Logo(LogoService.placeholder(s), false);
    byte[] png = logo.bytes();
    MediaType type = png.length > 1 && (png[0] & 0xff) == 0xff ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG;
    // a placeholder may stand in for a short upstream failure: never pin it in the browser for a year
    Duration maxAge = logo.stable() ? Duration.ofDays(365) : Duration.ofHours(1);
    return ResponseEntity.ok().contentType(type)
        .cacheControl(CacheControl.maxAge(maxAge).cachePrivate())
        .body(png);
  }
}
