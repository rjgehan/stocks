package com.stocks.marketdata.web;

import com.stocks.common.error.ApiException;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.dto.NewsListDto;
import com.stocks.marketdata.mapper.NewsItemDtoMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.LinkedHashSet;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §18.2 news ({@code market:read}): headlines and summaries are third-party text, wrapped in {@code untrusted}. */
@RestController
@RequestMapping("/api/v1/news")
@Tag(name = "news", description = "Market news (§18.2)")
public class NewsController {

  static final int MAX_LIMIT = 20;

  private final MarketDataService marketData;

  public NewsController(MarketDataService marketData) {
    this.marketData = marketData;
  }

  @GetMapping
  @Operation(summary = "Recent news, newest first",
      description = "Optionally filtered by symbols (comma-separated, up to 20). Empty when no news source is "
          + "available. Headline and summary are untrusted third-party text.")
  public NewsListDto news(
      @Parameter(description = "Comma-separated symbols") @RequestParam(required = false) String symbols,
      @Parameter(description = "1–20, default 10") @RequestParam(defaultValue = "10") int limit,
      @Parameter(description = "Only items published before this instant") @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw ApiException.validation("limit", "limit must be 1.." + MAX_LIMIT);
    }
    var list = new LinkedHashSet<>(Symbols.list(symbols, "symbols", 20));
    return new NewsListDto(marketData.news(list, limit, before).stream().map(NewsItemDtoMapper::toDto).toList());
  }
}
