package com.stocks.mcp.tools;

import com.stocks.common.domain.AssetType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.ScreenCursor;
import com.stocks.contract.marketdata.ScreenPreset;
import com.stocks.contract.marketdata.ScreenQuery;
import com.stocks.contract.marketdata.ScreenSort;
import com.stocks.contract.marketdata.Sector;
import com.stocks.contract.marketdata.SortOrder;
import com.stocks.contract.marketdata.dto.RecommendationsDto;
import com.stocks.contract.marketdata.dto.ScreenResultDto;
import io.modelcontextprotocol.common.McpTransportContext;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Phase 2 research tools (§20.3 #52–#53, scope {@code market:read}, read-only): the stock screener and analyst
 * recommendation trends (§15.10). Thin adapters over {@code MarketDataService} with the REST DTOs.
 */
@Component
public class ResearchTools implements McpToolSet {

  static final int MAX_SCREEN = 50;

  private final MarketDataService marketData;

  public ResearchTools(MarketDataService marketData) {
    this.marketData = marketData;
  }

  @McpTool(name = "screen_stocks", title = "Stock screener",
      description = "Screens the league's universe (~690 symbols: S&P 500, Nasdaq-100 extras, popular stocks, ADRs, "
          + "ETFs) by price, market cap, change, volume, relative volume (today's volume / 20-day average), sector "
          + "and type, sorted and paged. Presets: TOP_GAINERS, TOP_LOSERS, MOST_ACTIVE, UNUSUAL_VOLUME (relative "
          + "volume >= 2), LARGE_CAPS_UNDER_50 (cap >= $10B, price <= 50), MEGA_CAPS (cap >= $200B), ETFS; an explicit "
          + "argument replaces the preset's value. Change percents are fractions (0.05 = +5%), market caps dollars, "
          + "decimals as strings. Rows without a price are left out; warming=true means the first market snapshot is "
          + "still loading (try again shortly). For the next page pass cursor = the result's nextCursor.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Stock screener", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public ScreenResultDto screen_stocks(McpTransportContext ctx,
      @McpToolParam(required = false, description = "A preset screen") ScreenPreset preset,
      @McpToolParam(required = false, description = "Minimum last price, e.g. \"5\"") BigDecimal min_price,
      @McpToolParam(required = false, description = "Maximum last price") BigDecimal max_price,
      @McpToolParam(required = false, description = "Minimum market cap in dollars, e.g. \"10000000000\"")
      BigDecimal min_market_cap,
      @McpToolParam(required = false, description = "Maximum market cap in dollars") BigDecimal max_market_cap,
      @McpToolParam(required = false, description = "Minimum change vs the previous close, a fraction (\"0.02\")")
      BigDecimal min_change_pct,
      @McpToolParam(required = false, description = "Maximum change vs the previous close, a fraction (\"-0.02\")")
      BigDecimal max_change_pct,
      @McpToolParam(required = false, description = "Minimum volume today (shares)") Long min_volume,
      @McpToolParam(required = false, description = "Minimum 20-day average volume (shares)") Long min_avg_volume,
      @McpToolParam(required = false, description = "Minimum relative volume, e.g. \"2\"") BigDecimal min_rel_volume,
      @McpToolParam(required = false, description = "Only these GICS sectors") List<Sector> sectors,
      @McpToolParam(required = false, description = "Only these types: COMMON_STOCK, ETF, ADR") List<AssetType> types,
      @McpToolParam(required = false, description = "Sort column (default: the preset's, else MARKET_CAP)")
      ScreenSort sort,
      @McpToolParam(required = false, description = "ASC or DESC") SortOrder order,
      @McpToolParam(required = false, description = "1-50, default 20") Integer limit,
      @McpToolParam(required = false, description = "nextCursor of the previous result") String cursor) {
    int n = McpArgs.limit(limit, 20, 1, MAX_SCREEN, "limit");
    Set<Sector> sectorSet = EnumSet.noneOf(Sector.class);
    if (sectors != null) {
      sectors.stream().filter(s -> s != null).forEach(sectorSet::add);
    }
    Set<AssetType> typeSet = EnumSet.noneOf(AssetType.class);
    if (types != null) {
      types.stream().filter(t -> t != null).forEach(typeSet::add);
    }
    if (typeSet.contains(AssetType.OTHER) || typeSet.contains(AssetType.UNKNOWN)) {
      throw ApiException.validation("types", "types are COMMON_STOCK, ETF or ADR");
    }
    ScreenQuery q = new ScreenQuery(preset, min_price, max_price, min_market_cap, max_market_cap, min_change_pct,
        max_change_pct, min_volume, min_avg_volume, min_rel_volume, sectorSet, typeSet, sort, order, n,
        ScreenCursor.decode(cursor, "cursor")).validate(true);
    return ScreenResultDto.of(marketData.screen(q), ScreenCursor::encode);
  }

  @McpTool(name = "get_recommendations", title = "Analyst recommendations",
      description = "Monthly analyst recommendation trends for one stock (counts of strong buy, buy, hold, sell and "
          + "strong sell; newest month first, up to 6). periods is empty and source is \"none\" when no source covers "
          + "the symbol (for example an ETF). There are no price targets.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Analyst recommendations", readOnlyHint = true,
          destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public RecommendationsDto get_recommendations(McpTransportContext ctx,
      @McpToolParam(description = "Ticker, e.g. AAPL") String symbol) {
    String sym = McpArgs.symbol(symbol, "symbol");
    if (marketData.asset(sym).isEmpty()) {
      throw ApiException.of(ErrorCode.SYMBOL_NOT_FOUND, "Unknown symbol " + sym + ".")
          .withHint("Find the ticker with search_symbols.");
    }
    return RecommendationsDto.of(sym, marketData.recommendations(sym));
  }
}
