package com.stocks.contract.marketdata.dto;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.DataQuality;
import com.stocks.contract.marketdata.ScreenPreset;
import com.stocks.contract.marketdata.ScreenResult;
import com.stocks.contract.marketdata.ScreenSort;
import com.stocks.contract.marketdata.Sector;
import com.stocks.contract.marketdata.SortOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.IntFunction;

/**
 * §15.10 / §18.3 {@code ScreenResult} ({@code GET /screener}, MCP {@code screen_stocks}): one page of screened
 * symbols with the total match count, the opaque {@code nextCursor} (null on the last page), {@code warming} while the
 * first snapshot pass is still running, and the universe coverage.
 */
public record ScreenResultDto(ScreenPreset preset, ScreenSort sort, SortOrder order, int total,
                              List<ScreenerRowDto> items, String nextCursor, Instant asOf, boolean warming,
                              ScreenCoverageDto coverage) {

  /** One row; {@code changePct} is a fraction, {@code relVolume} = volume ÷ average volume on the same basis. */
  public record ScreenerRowDto(String symbol, String name, AssetType type, String exchange, Sector sector,
                               BigDecimal price, BigDecimal change, BigDecimal changePct, Long volume,
                               boolean volumeIsPartial, Long avgVolume, BigDecimal relVolume, BigDecimal marketCap,
                               DataQuality quality, Instant asOf) {

    public static ScreenerRowDto of(ScreenResult.Row r) {
      return new ScreenerRowDto(r.symbol(), r.name(), r.type(), r.exchange(), r.sector(), r.price(), r.change(),
          r.changePct(), r.volume(), r.volumeIsPartial(), r.avgVolume(), r.relVolume(), r.marketCap(), r.quality(),
          r.asOf());
    }
  }

  /** How much of the universe has data: its size, priced symbols, with a market cap, with an average volume. */
  public record ScreenCoverageDto(int universe, int priced, int withMarketCap, int withAvgVolume) {}

  /** The mapping every channel uses (REST, MCP); {@code cursor} encodes an offset. */
  public static ScreenResultDto of(ScreenResult r, IntFunction<String> cursor) {
    ScreenResult.Coverage c = r.coverage();
    return new ScreenResultDto(r.preset(), r.sort(), r.order(), r.total(),
        r.rows().stream().map(ScreenerRowDto::of).toList(),
        r.nextOffset() == null ? null : cursor.apply(r.nextOffset()), r.asOf(), r.warming(),
        new ScreenCoverageDto(c.universe(), c.priced(), c.withMarketCap(), c.withAvgVolume()));
  }
}
