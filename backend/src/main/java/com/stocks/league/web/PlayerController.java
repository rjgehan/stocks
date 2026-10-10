package com.stocks.league.web;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.dto.PlayerProfileDto;
import com.stocks.contract.portfolio.dto.PublicHistoryDto;
import com.stocks.contract.portfolio.dto.PublicTradeDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.portfolio.PlayerDirectory;
import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.2 {@code /players/**} ({@code league:read}): public player data only (§16.7). Trades appear after the feed
 * delay; holdings are previous-EOD weights; working orders and live positions are never shown.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "league", description = "Leaderboard, players and the league feed")
public class PlayerController {

  private final PlayerDirectory players;
  private final PerformanceQueries performance;

  public PlayerController(PlayerDirectory players, PerformanceQueries performance) {
    this.players = players;
    this.performance = performance;
  }

  @GetMapping("/players")
  @Operation(summary = "League players", description = "ACTIVE and FROZEN players (DISABLED ones are hidden), by "
      + "handle.")
  public List<PlayerSummaryDto> listPlayers(
      @Parameter(description = "HUMAN or AI; both when absent")
      @RequestParam(name = "kind", required = false) PlayerKind kind) {
    return players.listed(kind).stream().map(PlayerRow::summary).toList();
  }

  @GetMapping("/players/{handle}")
  @Operation(summary = "A player's public profile", description = "Stats per period (return, P&L, rank), deposits, "
      + "previous-EOD holdings as weights, and recent delayed trades.")
  public PlayerProfileDto getPlayerProfile(@PathVariable("handle") String handle) {
    return performance.profile(handle, CurrentPlayer.require().playerId());
  }

  @GetMapping("/players/{handle}/history")
  @Operation(summary = "A player's unit-value curve", description = "Unit values across runs, plus equity unless "
      + "holdings visibility is HIDDEN.")
  public PublicHistoryDto getPlayerHistory(
      @PathVariable("handle") String handle,
      @Parameter(description = "1D, 1W, 1M (default), 3M, YTD, 1Y or ALL")
      @RequestParam(name = "range", required = false) HistoryRange range) {
    return performance.publicHistory(handle, range == null ? HistoryRange.M1 : range,
        CurrentPlayer.require().playerId());
  }

  @GetMapping("/players/{handle}/trades")
  @Operation(summary = "A player's trades, delayed", description = "Executions older than the feed delay "
      + "(15 min), newest first; `viaApi` marks API-key/MCP orders; the rationale only when it is public.")
  public Page<PublicTradeDto> getPlayerTrades(
      @PathVariable("handle") String handle,
      @RequestParam(name = "cursor", required = false) String cursor,
      @Parameter(description = "1..200, default 50")
      @RequestParam(name = "limit", required = false) Integer limit) {
    if (limit != null && (limit < 1 || limit > Page.MAX_LIMIT)) {
      throw ApiException.validation("limit", "limit must be between 1 and 200");
    }
    return performance.publicTrades(handle, Cursor.parse(cursor), Page.clampLimit(limit));
  }
}
