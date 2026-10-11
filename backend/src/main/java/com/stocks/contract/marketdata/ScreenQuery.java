package com.stocks.contract.marketdata;

import com.stocks.common.domain.AssetType;
import com.stocks.common.error.ApiException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * One screener request (§15.10). Every member may be null (no filter, or the preset's / default value). Percent
 * changes are fractions ({@code 0.05} = +5%); market caps are dollars; volumes are shares. {@code offset} is the
 * decoded cursor (0 for the first page).
 */
public record ScreenQuery(ScreenPreset preset, BigDecimal minPrice, BigDecimal maxPrice, BigDecimal minMarketCap,
                          BigDecimal maxMarketCap, BigDecimal minChangePct, BigDecimal maxChangePct, Long minVolume,
                          Long minAvgVolume, BigDecimal minRelVolume, Set<Sector> sectors, Set<AssetType> types,
                          ScreenSort sort, SortOrder order, int limit, int offset) {

  public ScreenQuery {
    sectors = sectors == null ? Set.of() : Set.copyOf(sectors);
    types = types == null ? Set.of() : Set.copyOf(types);
    limit = Math.max(1, limit);
    offset = Math.max(0, offset);
  }

  /**
   * Checks the bounds (§15.10): no negative price, market cap, volume or relative volume, and every {@code min} ≤ its
   * {@code max}; {@code VALIDATION_FAILED} naming the parameter otherwise, in the channel's spelling ({@code snake}:
   * MCP's {@code min_price}, else REST's {@code minPrice}).
   */
  public ScreenQuery validate(boolean snake) {
    Function<String, String> n = camel -> snake
        ? camel.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT) : camel;
    nonNegative(minPrice, n.apply("minPrice"));
    nonNegative(maxPrice, n.apply("maxPrice"));
    nonNegative(minMarketCap, n.apply("minMarketCap"));
    nonNegative(maxMarketCap, n.apply("maxMarketCap"));
    nonNegative(minVolume == null ? null : BigDecimal.valueOf(minVolume), n.apply("minVolume"));
    nonNegative(minAvgVolume == null ? null : BigDecimal.valueOf(minAvgVolume), n.apply("minAvgVolume"));
    nonNegative(minRelVolume, n.apply("minRelVolume"));
    ordered(minPrice, maxPrice, n.apply("maxPrice"));
    ordered(minMarketCap, maxMarketCap, n.apply("maxMarketCap"));
    ordered(minChangePct, maxChangePct, n.apply("maxChangePct"));
    return this;
  }

  private static void nonNegative(BigDecimal v, String field) {
    if (v != null && v.signum() < 0) {
      throw ApiException.validation(field, "must not be negative");
    }
  }

  private static void ordered(BigDecimal min, BigDecimal max, String field) {
    if (min != null && max != null && min.compareTo(max) > 0) {
      throw ApiException.validation(field, "must be at least the minimum");
    }
  }

  /** Only a preset (or nothing), first page. */
  public static ScreenQuery of(ScreenPreset preset, int limit) {
    return new ScreenQuery(preset, null, null, null, null, null, null, null, null, null, Set.of(), Set.of(), null,
        null, limit, 0);
  }
}
