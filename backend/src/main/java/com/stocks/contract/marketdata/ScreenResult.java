package com.stocks.contract.marketdata;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.DataQuality;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A screen's answer (§15.10), before DTO mapping: the page of rows, the total number of matches, the effective
 * preset/sort/order, the next offset (null on the last page), and how much of the universe has data.
 */
public record ScreenResult(ScreenPreset preset, ScreenSort sort, SortOrder order, int total, List<Row> rows,
                           Integer nextOffset, Instant asOf, boolean warming, Coverage coverage) {

  public ScreenResult {
    rows = rows == null ? List.of() : List.copyOf(rows);
  }

  /** One screened symbol. {@code changePct} is a fraction; {@code marketCap} dollars; volumes shares. */
  public record Row(String symbol, String name, AssetType type, String exchange, Sector sector, BigDecimal price,
                    BigDecimal change, BigDecimal changePct, Long volume, boolean volumeIsPartial, Long avgVolume,
                    BigDecimal relVolume, BigDecimal marketCap, DataQuality quality, Instant asOf) {}

  /** Universe size, symbols with a price, with a market cap and with an average volume. */
  public record Coverage(int universe, int priced, int withMarketCap, int withAvgVolume) {}
}
