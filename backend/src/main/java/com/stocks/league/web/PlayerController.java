package com.stocks.league.web;

import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.PublicPortfolioQueries;
import com.stocks.contract.portfolio.dto.PlayerProfileDto;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.CalendarView;
import com.stocks.contract.portfolio.dto.PublicHistoryDto;
import com.stocks.contract.portfolio.dto.PublicOrderDto;
import com.stocks.contract.portfolio.dto.PublicPortfolioDto;
import com.stocks.contract.portfolio.dto.PublicTradeDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.trading.ActivityFilter;
import com.stocks.contract.trading.OrderStatusFilter;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.portfolio.PlayerDirectory;
import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import com.stocks.portfolio.web.PnlAnalysisController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.2 {@code /players/**} ({@code league:read}): public player data (public mode, §16.7). The live portfolio,
 * orders, activity and P&amp;L are never delayed; trades follow the feed delay (none by default); profile holdings
 * are the previous-EOD rows with weights and quantities.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "league", description = "Leaderboard, players and the league feed")
public class PlayerController {

  private final PlayerDirectory players;
  private final PerformanceQueries performance;
  private final PublicPortfolioQueries portfolios;

  public PlayerController(PlayerDirectory players, PerformanceQueries performance,
                          PublicPortfolioQueries portfolios) {
    this.players = players;
    this.performance = performance;
    this.portfolios = portfolios;
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
      + "previous-EOD holdings with weights and quantities, and recent trades.")
  public PlayerProfileDto getPlayerProfile(@PathVariable("handle") String handle) {
    return performance.profile(handle, CurrentPlayer.require().playerId());
  }

  @GetMapping("/players/{handle}/history")
  @Operation(summary = "A player's unit-value curve", description = "Unit values across runs, plus equity.")
  public PublicHistoryDto getPlayerHistory(
      @PathVariable("handle") String handle,
      @Parameter(description = "1D, 1W, 1M (default), 3M, YTD, 1Y or ALL")
      @RequestParam(name = "range", required = false) HistoryRange range) {
    return performance.publicHistory(handle, range == null ? HistoryRange.M1 : range,
        CurrentPlayer.require().playerId());
  }

  @GetMapping("/players/{handle}/trades")
  @Operation(summary = "A player's trades", description = "Executions newest first, after `social.tradeFeedDelay` "
      + "(default none); `viaApi` marks API-key/MCP orders; the rationale only when it is public.")
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

  @GetMapping("/players/{handle}/portfolio")
  @Operation(summary = "A player's live portfolio", description = "Public mode (§16.7): cash, buying power, equity "
      + "and P&L, every position with live marks, and open orders with their rationales. Live, never delayed. "
      + "PLAYER_NOT_FOUND for an unknown or disabled player.")
  public PublicPortfolioDto getPlayerPortfolio(@PathVariable("handle") String handle) {
    return portfolios.portfolio(handle);
  }

  @GetMapping("/players/{handle}/orders")
  @Operation(summary = "A player's order history", description = "Every stored order of every run, newest first, "
      + "with fills, fees, group legs and the rationale (inside untrusted). status OPEN, CLOSED or ALL (default); "
      + "symbol optional. Live, never delayed.")
  public Page<PublicOrderDto> getPlayerOrders(
      @PathVariable("handle") String handle,
      @Parameter(description = "OPEN, CLOSED or ALL (default)")
      @RequestParam(name = "status", required = false) OrderStatusFilter status,
      @Parameter(description = "Only this ticker")
      @RequestParam(name = "symbol", required = false) String symbol,
      @RequestParam(name = "cursor", required = false) String cursor,
      @Parameter(description = "1..200, default 50")
      @RequestParam(name = "limit", required = false) Integer limit) {
    if (limit != null && (limit < 1 || limit > Page.MAX_LIMIT)) {
      throw ApiException.validation("limit", "limit must be between 1 and 200");
    }
    return portfolios.orders(handle, status, symbol, Cursor.parse(cursor), Page.clampLimit(limit));
  }

  @GetMapping("/players/{handle}/activities")
  @Operation(summary = "A player's account activity", description = "The active run's ledger and account events "
      + "(fills, funding, deposits, withdrawals, adjustments, dividends, cash in lieu, splits, renames, GFVs, resets, "
      + "restrictions), newest first. Admin and player memos are left out. Live.")
  public Page<ActivityDto> getPlayerActivities(
      @PathVariable("handle") String handle,
      @RequestParam(name = "types", required = false) Set<ActivityType> types,
      @Parameter(description = "ET date, inclusive")
      @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @Parameter(description = "ET date, inclusive")
      @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(name = "cursor", required = false) String cursor,
      @Parameter(description = "1..200, default 50")
      @RequestParam(name = "limit", required = false) Integer limit) {
    return portfolios.activities(handle, new ActivityFilter(types == null ? Set.of() : types, from, to),
        Cursor.parse(cursor), Page.clampLimit(limit));
  }

  @GetMapping("/players/{handle}/pnl-analysis")
  @Operation(summary = "A player's P&L analysis", description = "A player's earnings: the active run's "
      + "time-weighted return and P&L over the range vs SPY and QQQ (total-return indexes), and the P&L by symbol "
      + "(realized, change of unrealized, dividends); the symbols' totals plus `other` (P&L corrections) add up to "
      + "`pnl`. ACCOUNT_NOT_FOUND without an active run.")
  public PnlAnalysisDto getPlayerPnlAnalysis(
      @PathVariable("handle") String handle,
      @Parameter(description = "1D, 1W, 1M (default), 3M, YTD, 1Y or ALL")
      @RequestParam(name = "range", required = false) HistoryRange range) {
    return portfolios.pnlAnalysis(handle, range == null ? HistoryRange.M1 : range);
  }

  @GetMapping("/players/{handle}/pnl-calendar")
  @Operation(summary = "A player's P&L calendar", description = "DAY: one cell per trading day of `month` "
      + "(YYYY-MM, default the current ET month); MONTH: one cell per month of `year`. A day's P&L never counts "
      + "deposits or withdrawals (shown as `flows`) nor splits or renames; today's cell is live until the EOD "
      + "snapshot. ACCOUNT_NOT_FOUND without an active run.")
  public PnlCalendarDto getPlayerPnlCalendar(
      @PathVariable("handle") String handle,
      @Parameter(description = "DAY (default) or MONTH")
      @RequestParam(name = "view", required = false) CalendarView view,
      @Parameter(description = "YYYY-MM (DAY view)")
      @RequestParam(name = "month", required = false) String month,
      @Parameter(description = "2000..2100 (MONTH view)")
      @RequestParam(name = "year", required = false) Integer year) {
    return portfolios.pnlCalendar(handle, view == null ? CalendarView.DAY : view, PnlAnalysisController.month(month),
        PnlAnalysisController.year(year));
  }
}
