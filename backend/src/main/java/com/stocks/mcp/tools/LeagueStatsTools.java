package com.stocks.mcp.tools;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.error.ApiException;
import com.stocks.contract.portfolio.FeedReactionService;
import com.stocks.contract.portfolio.LeagueStatsQueries;
import com.stocks.contract.portfolio.PnlAnalysisQueries;
import com.stocks.contract.portfolio.dto.FeedReactionsDto;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.Reaction;
import com.stocks.contract.portfolio.dto.PlayerStatsDto;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.CalendarView;
import com.stocks.contract.portfolio.dto.RaceChartDto;
import com.stocks.contract.portfolio.dto.RaceChartDto.RaceSeriesDto;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.mcp.Untrusted;
import io.modelcontextprotocol.common.McpTransportContext;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Phase 2 league-stats tools (§20.3 #43–#48): your stats and another player's (§16.8), the race chart (§16.9), your
 * P&amp;L calendar and analysis (§16.10), and reacting to a feed item (§16.11, scope {@code trade}). Built by the same
 * WS4 services as REST; other players' display names are clipped once more ({@link Untrusted}).
 */
@Component
public class LeagueStatsTools implements McpToolSet {

  private static final Pattern MONTH = Pattern.compile("^\\d{4}-\\d{2}$");

  private final LeagueStatsQueries stats;
  private final PnlAnalysisQueries analysis;
  private final FeedReactionService reactions;
  private final AccountQueries accounts;

  public LeagueStatsTools(LeagueStatsQueries stats, PnlAnalysisQueries analysis, FeedReactionService reactions,
                          AccountQueries accounts) {
    this.stats = stats;
    this.analysis = analysis;
    this.reactions = reactions;
    this.accounts = accounts;
  }

  @McpTool(name = "get_my_stats", title = "My league stats",
      description = "Your league stats for a leaderboard period (default ALL): time-weighted return and P&L as on "
          + "the leaderboard, max drawdown, Sharpe ratio and volatility (annualized from daily unit-value returns; "
          + "null until 20 daily returns exist), win rate, profit factor (gross profit / gross loss of closing "
          + "sells; null without a loss), closed lots with the average hold in seconds, best and worst trade, rank "
          + "and the EOD rank history. Returns are fractions (0.0123 = +1.23%).",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "My league stats", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public PlayerStatsDto get_my_stats(McpTransportContext ctx,
      @McpToolParam(required = false, description = "TODAY, WEEK, MONTH, YTD, SEASON or ALL (default)")
      LeaderboardPeriod period) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    return clip(stats.myStats(caller.playerId(), period == null ? LeaderboardPeriod.ALL : period));
  }

  @McpTool(name = "get_player_stats", title = "Player stats",
      description = "A player's league stats for a period (default ALL), as get_my_stats; best and worst trades "
          + "include sells older than the league's feed delay (none by default). PLAYER_NOT_FOUND for an unknown "
          + "handle.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Player stats", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public PlayerStatsDto get_player_stats(McpTransportContext ctx,
      @McpToolParam(description = "The player's handle") String handle,
      @McpToolParam(required = false, description = "TODAY, WEEK, MONTH, YTD, SEASON or ALL (default)")
      LeaderboardPeriod period) {
    long viewer = McpArgs.caller(ctx).playerId();
    return clip(stats.playerStats(McpArgs.handle(handle, "handle"),
        period == null ? LeaderboardPeriod.ALL : period, viewer));
  }

  @McpTool(name = "get_race_chart", title = "Race chart",
      description = "Every player's unit-value curve over a period normalized to 0 at the period start (deposits "
          + "never move a line; the last point equals the player's leaderboard return), ordered by rank, with each "
          + "player's EOD rank history and SPY's total return over the same window.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Race chart", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public RaceChartDto get_race_chart(McpTransportContext ctx,
      @McpToolParam(required = false, description = "TODAY, WEEK (default), MONTH, YTD, SEASON or ALL")
      LeaderboardPeriod period,
      @McpToolParam(required = false, description = "ALL (default), HUMAN or AI") KindFilter kind) {
    McpArgs.caller(ctx);
    RaceChartDto r = stats.race(period == null ? LeaderboardPeriod.WEEK : period, kind == null ? KindFilter.ALL : kind);
    return r == null ? null : new RaceChartDto(r.period(), r.kind(), r.periodStart(), r.asOf(), r.benchmark(),
        r.series() == null ? null : r.series().stream().map(s -> s == null ? null : new RaceSeriesDto(
            Untrusted.player(s.player()), s.rank(), s.ret(), s.rebased(), s.partialPeriod(), s.points(), s.ranks()))
            .toList());
  }

  @McpTool(name = "get_pnl_calendar", title = "P&L calendar",
      description = "Your daily (view DAY, one cell per trading day of month YYYY-MM, default the current month) or "
          + "monthly (view MONTH, one cell per month of year) P&L and return for your current run. A day's P&L "
          + "never counts deposits or withdrawals (they are in flows) and never counts splits or symbol changes; "
          + "today's cell is live until the end-of-day snapshot.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "P&L calendar", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public PnlCalendarDto get_pnl_calendar(McpTransportContext ctx,
      @McpToolParam(required = false, description = "DAY (default) or MONTH") String view,
      @McpToolParam(required = false, description = "YYYY-MM for the DAY view, default the current month")
      String month,
      @McpToolParam(required = false, description = "The year for the MONTH view, default the current year")
      Integer year) {
    long accountId = McpArgs.accountId(accounts, McpArgs.caller(ctx));
    return analysis.calendar(accountId, view(view), month(month), year(year));
  }

  @McpTool(name = "get_pnl_analysis", title = "P&L analysis",
      description = "Your current run over a range (default 1M): time-weighted return and P&L with the curve, SPY "
          + "and QQQ total returns over the same window, and the P&L by symbol (realized, change of unrealized, "
          + "dividends; plus 'other' for P&L corrections).",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "P&L analysis", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public PnlAnalysisDto get_pnl_analysis(McpTransportContext ctx,
      @McpToolParam(required = false, description = "1D, 1W, 1M (default), 3M, YTD, 1Y or ALL") HistoryRange range) {
    long accountId = McpArgs.accountId(accounts, McpArgs.caller(ctx));
    return analysis.analysis(accountId, range == null ? HistoryRange.M1 : range);
  }

  @McpTool(name = "react_to_feed_item", title = "React to a feed item",
      description = "Sets your emoji reaction on a league feed item (item_id is the feed item's id): ROCKET, FIRE, "
          + "GEM, CLAP, LAUGH or SKULL, replacing any reaction you had on it; NONE removes yours. One reaction per "
          + "player per item; idempotent. Returns the item's reactions.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "React to a feed item", readOnlyHint = false,
          destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public FeedReactionsDto react_to_feed_item(McpTransportContext ctx,
      @McpToolParam(description = "The feed item's id (FeedItem.id)") String item_id,
      @McpToolParam(description = "ROCKET, FIRE, GEM, CLAP, LAUGH, SKULL, or NONE to remove yours")
      String reaction) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    String item = McpArgs.text(item_id, 32, "item_id");
    String r = reaction == null ? "" : reaction.strip().toUpperCase(Locale.ROOT);
    if (r.equals("NONE")) {
      return Untrusted.reactions(reactions.unreact(caller.playerId(), item));
    }
    Reaction value;
    try {
      value = Reaction.valueOf(r);
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("reaction", "reaction must be ROCKET, FIRE, GEM, CLAP, LAUGH, SKULL or NONE.");
    }
    return Untrusted.reactions(reactions.react(caller.playerId(), item, value));
  }

  // ------------------------------------------------------------------------------------------- helpers

  private static PlayerStatsDto clip(PlayerStatsDto s) {
    return s == null ? null : new PlayerStatsDto(Untrusted.player(s.player()), s.period(), s.periodStart(), s.asOf(),
        s.ret(), s.pnl(), s.maxDrawdown(), s.partialPeriod(), s.rebased(), s.sharpe(), s.volatility(),
        s.dailyReturns(), s.sharpeMinDays(), s.closingSells(), s.wins(), s.winRate(), s.grossProfit(), s.grossLoss(),
        s.profitFactor(), s.closedLots(), s.avgHoldSeconds(), s.tradesAsOf(), s.bestTrade(), s.worstTrade(),
        s.rank(), s.rankChange(), s.rankHistory());
  }

  static CalendarView view(String raw) {
    if (raw == null || raw.isBlank()) {
      return CalendarView.DAY;
    }
    try {
      return CalendarView.valueOf(raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("view", "view must be DAY or MONTH.");
    }
  }

  static YearMonth month(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String s = raw.strip();
    try {
      if (MONTH.matcher(s).matches()) {
        YearMonth ym = YearMonth.parse(s);
        if (ym.getYear() >= 2000 && ym.getYear() <= 2100) {
          return ym;
        }
      }
    } catch (DateTimeParseException e) {
      // falls through to the validation error
    }
    throw ApiException.validation("month", "month must be YYYY-MM.");
  }

  static Integer year(Integer year) {
    if (year != null && (year < 2000 || year > 2100)) {
      throw ApiException.validation("year", "year must be between 2000 and 2100.");
    }
    return year;
  }
}
