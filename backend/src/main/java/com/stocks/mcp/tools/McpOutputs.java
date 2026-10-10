package com.stocks.mcp.tools;

import com.stocks.common.domain.Timeframe;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.marketdata.dto.AssetSummaryDto;
import com.stocks.contract.marketdata.dto.TradingDayDto;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.contract.trading.dto.RunDto;
import com.stocks.contract.watchlist.dto.WatchlistDto;
import java.time.Instant;
import java.util.List;

/**
 * MCP-only result wrappers (§20.3). MCP requires {@code structuredContent} to be a JSON <b>object</b> and an
 * {@code outputSchema} of {@code "type": "object"} (MCP 2025-06-18 and later), so the tools whose §20.3 output is a bare
 * array ({@code TradingDay[]}, {@code AssetSummary[]}, {@code Position[]}, {@code Run[]}, {@code PlayerSummary[]},
 * {@code Watchlist[]}) return it under one named member, and the small write results are objects too. The members
 * are the §18.3 DTOs, unchanged.
 */
public final class McpOutputs {

  private McpOutputs() {}

  /** {@code get_calendar}: {@code {days: TradingDay[]}}. */
  public record CalendarDaysDto(List<TradingDayDto> days) {}

  /** {@code search_symbols}: {@code {assets: AssetSummary[]}}, best match first. */
  public record AssetSearchDto(List<AssetSummaryDto> assets) {}

  /** {@code get_positions}: {@code {positions: Position[]}}. */
  public record PositionsDto(List<PositionDto> positions) {}

  /** {@code get_runs}: {@code {runs: Run[]}}, oldest run first. */
  public record RunsDto(List<RunDto> runs) {}

  /** {@code list_players}: {@code {players: PlayerSummary[]}}. */
  public record PlayersDto(List<PlayerSummaryDto> players) {}

  /** {@code get_watchlists}: {@code {watchlists: Watchlist[]}} in sort order. */
  public record WatchlistsDto(List<WatchlistDto> watchlists) {}

  /** {@code delete_watchlist}: {@code {deleted}}; false if the list did not exist. */
  public record DeletedDto(boolean deleted) {}

  /** {@code mark_notifications_read}: {@code {marked}}, how many changed from unread to read. */
  public record MarkedDto(int marked) {}

  /**
   * {@code get_bars} (§20.3): compact bars. {@code columns} is always {@code ["t","o","h","l","c","v"]}; each row is
   * {@code [t (ISO instant), o, h, l, c (decimal strings), v (integer)]}. {@code tailFrom}: the first bar built from
   * the live IEX tail (null if every bar is consolidated SIP history).
   */
  public record CompactBarsDto(String symbol, Timeframe timeframe, List<String> columns, List<List<Object>> rows,
                               Instant tailFrom, String source) {

    public static final List<String> COLUMNS = List.of("t", "o", "h", "l", "c", "v");
  }
}
