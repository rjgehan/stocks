package com.stocks.marketdata.web;

import com.stocks.common.domain.AssetType;
import com.stocks.common.error.ApiException;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.ScreenCursor;
import com.stocks.contract.marketdata.ScreenPreset;
import com.stocks.contract.marketdata.ScreenQuery;
import com.stocks.contract.marketdata.ScreenSort;
import com.stocks.contract.marketdata.Sector;
import com.stocks.contract.marketdata.SortOrder;
import com.stocks.contract.marketdata.dto.ScreenResultDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phase 2 (§15.10, §18.2): the stock screener over the universe ({@code market:read}, a normal read). */
@RestController
@RequestMapping("/api/v1/market/screener")
@Tag(name = "screener", description = "Stock screener over the league's universe (§15.10)")
public class ScreenerController {

  static final int MAX_LIMIT = 100;

  private final MarketDataService marketData;

  public ScreenerController(MarketDataService marketData) {
    this.marketData = marketData;
  }

  @GetMapping
  @Operation(summary = "Screen stocks",
      description = "Screens the universe (S&P 500, Nasdaq-100 extras, popular stocks, ADRs and ETFs; ~690 symbols) "
          + "with the latest quotes and 20-day volume and company statistics. A preset sets filters and a sort; an "
          + "explicit parameter replaces the preset's value. changePct values are fractions (0.05 = +5%), market caps "
          + "dollars. A filter on a column excludes rows where it is unknown; rows without a price are never returned. "
          + "Null sort keys sort last; ties by symbol. warming=true while the first snapshot pass of the universe is "
          + "still running. Errors: VALIDATION_FAILED.")
  public ScreenResultDto screen(
      @Parameter(description = "TOP_GAINERS, TOP_LOSERS, MOST_ACTIVE, UNUSUAL_VOLUME, LARGE_CAPS_UNDER_50, MEGA_CAPS "
          + "or ETFS") @RequestParam(required = false) ScreenPreset preset,
      @Parameter(description = "Minimum last price") @RequestParam(required = false) BigDecimal minPrice,
      @Parameter(description = "Maximum last price") @RequestParam(required = false) BigDecimal maxPrice,
      @Parameter(description = "Minimum market cap (dollars)") @RequestParam(required = false) BigDecimal minMarketCap,
      @Parameter(description = "Maximum market cap (dollars)") @RequestParam(required = false) BigDecimal maxMarketCap,
      @Parameter(description = "Minimum change vs the previous close, a fraction")
      @RequestParam(required = false) BigDecimal minChangePct,
      @Parameter(description = "Maximum change vs the previous close, a fraction")
      @RequestParam(required = false) BigDecimal maxChangePct,
      @Parameter(description = "Minimum volume today (shares)") @RequestParam(required = false) Long minVolume,
      @Parameter(description = "Minimum 20-day average volume (shares)") @RequestParam(required = false)
      Long minAvgVolume,
      @Parameter(description = "Minimum relative volume (volume ÷ 20-day average)") @RequestParam(required = false)
      BigDecimal minRelVolume,
      @Parameter(description = "GICS sectors, comma-separated") @RequestParam(required = false) List<Sector> sectors,
      @Parameter(description = "COMMON_STOCK, ETF and/or ADR, comma-separated") @RequestParam(required = false)
      List<AssetType> types,
      @Parameter(description = "Sort column (default: the preset's, else MARKET_CAP)") @RequestParam(required = false)
      ScreenSort sort,
      @Parameter(description = "ASC or DESC (default: the preset's, else DESC; SYMBOL defaults to ASC)")
      @RequestParam(required = false) SortOrder order,
      @Parameter(description = "Opaque cursor from nextCursor") @RequestParam(required = false) String cursor,
      @Parameter(description = "1–100, default 25") @RequestParam(defaultValue = "25") int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw ApiException.validation("limit", "limit must be 1.." + MAX_LIMIT);
    }
    ScreenQuery q = new ScreenQuery(preset, minPrice, maxPrice, minMarketCap, maxMarketCap, minChangePct,
        maxChangePct, minVolume, minAvgVolume, minRelVolume, set(sectors, Sector.class), types(types), sort, order,
        limit, ScreenCursor.decode(cursor, "cursor")).validate(false);
    return ScreenResultDto.of(marketData.screen(q), ScreenCursor::encode);
  }

  /** The screenable types: COMMON_STOCK, ETF, ADR ({@code VALIDATION_FAILED} for OTHER or UNKNOWN). */
  static Set<AssetType> types(List<AssetType> types) {
    Set<AssetType> out = set(types, AssetType.class);
    if (out.contains(AssetType.OTHER) || out.contains(AssetType.UNKNOWN)) {
      throw ApiException.validation("types", "types are COMMON_STOCK, ETF or ADR");
    }
    return out;
  }

  private static <E extends Enum<E>> Set<E> set(List<E> values, Class<E> type) {
    Set<E> out = EnumSet.noneOf(type);
    if (values != null) {
      values.stream().filter(v -> v != null).forEach(out::add);
    }
    return out;
  }
}
