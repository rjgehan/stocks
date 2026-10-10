package com.stocks.mcp.tools;

import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.HistoryRange;
import com.stocks.common.error.ApiException;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Page;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.ActivityFilter;
import com.stocks.contract.trading.ExecutionFilter;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.ViolationDto;
import com.stocks.contract.trading.dto.ViolationsDto;
import com.stocks.mcp.tools.McpOutputs.PositionsDto;
import com.stocks.mcp.tools.McpOutputs.RunsDto;
import io.modelcontextprotocol.common.McpTransportContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Account tools (§20.3 #13, #14, #17–#21; scope {@code account:read}): the caller's own account, positions,
 * executions, activity, good-faith violations, runs and portfolio history, from WS3's {@code AccountQueries} /
 * {@code OrderQueries} and WS4's {@code PerformanceQueries}, the same services and DTOs as {@code /account/**}. Every
 * tool is read-only and only ever sees the caller's ACTIVE account.
 */
@Component
public class AccountTools implements McpToolSet {

  private final AccountQueries accounts;
  private final OrderQueries orders;
  private final PerformanceQueries performance;
  private final TimeSource time;

  public AccountTools(AccountQueries accounts, OrderQueries orders, PerformanceQueries performance,
                      TimeSource time) {
    this.accounts = accounts;
    this.orders = orders;
    this.performance = performance;
    this.time = time;
  }

  @McpTool(name = "get_account", title = "Account",
      description = "Your cash account, valued live: equity, cash (total, settled, unsettled, settled available, "
          + "withdrawable), open buy holds, buying power, net contributions (deposits count here, not as profit), "
          + "total and day P&L and returns (fractions: 0.0123 = +1.23%), restriction and good-faith-violation "
          + "counts. All amounts are decimal strings in USD.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Account", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public AccountDto get_account(McpTransportContext ctx) {
    return accounts.account(McpArgs.accountId(accounts, McpArgs.caller(ctx)));
  }

  @McpTool(name = "get_positions", title = "Positions",
      description = "Your open positions: quantity (fractional to 5 dp), quantity available to sell (not reserved "
          + "by open sell orders), average cost, cost basis, last price, mark and its quality, market value, "
          + "unrealized and day P&L, and portfolio weight.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Positions", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public PositionsDto get_positions(McpTransportContext ctx) {
    return new PositionsDto(accounts.positions(McpArgs.accountId(accounts, McpArgs.caller(ctx))));
  }

  @McpTool(name = "get_executions", title = "Executions",
      description = "Your fills, newest first: price, quantity, principal, fees, net cash, realized P&L on sells, "
          + "trade and settlement dates, and the quote each fill used. For the next page, pass nextCursor as cursor.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Executions", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public Page<ExecutionDto> get_executions(McpTransportContext ctx,
      @McpToolParam(required = false, description = "Only this ticker") String symbol,
      @McpToolParam(required = false, description = "From this instant (ISO-8601)") Instant from,
      @McpToolParam(required = false, description = "Up to this instant (ISO-8601)") Instant to,
      @McpToolParam(required = false, description = "1-100, default 50") Integer limit,
      @McpToolParam(required = false, description = "nextCursor of the previous page") String cursor) {
    long accountId = McpArgs.accountId(accounts, McpArgs.caller(ctx));
    int n = McpArgs.limit(limit, 50, 1, 100, "limit");
    return orders.executions(accountId, new ExecutionFilter(McpArgs.optionalSymbol(symbol, "symbol"), from, to),
        McpArgs.cursor(cursor), n);
  }

  @McpTool(name = "get_activity", title = "Account activity",
      description = "Your account history, newest first: fills, initial funding, deposits, withdrawals, adjustments, "
          + "dividends, cash in lieu, splits, symbol changes, good-faith violations, resets and restrictions, with "
          + "the cash balance after each. Filter by types and ET dates; for the next page, pass nextCursor as cursor.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Account activity", readOnlyHint = true,
          destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public Page<ActivityDto> get_activity(McpTransportContext ctx,
      @McpToolParam(required = false, description = "Only these activity types") List<ActivityType> types,
      @McpToolParam(required = false, description = "From this ET date (YYYY-MM-DD)") LocalDate from,
      @McpToolParam(required = false, description = "Up to this ET date (YYYY-MM-DD)") LocalDate to,
      @McpToolParam(required = false, description = "1-100, default 50") Integer limit,
      @McpToolParam(required = false, description = "nextCursor of the previous page") String cursor) {
    long accountId = McpArgs.accountId(accounts, McpArgs.caller(ctx));
    int n = McpArgs.limit(limit, 50, 1, 100, "limit");
    Set<ActivityType> set = types == null || types.isEmpty() ? EnumSet.noneOf(ActivityType.class)
        : EnumSet.copyOf(types);
    return accounts.activities(accountId, new ActivityFilter(set, from, to), McpArgs.cursor(cursor), n);
  }

  @McpTool(name = "get_violations", title = "Good-faith violations",
      description = "Your good-faith violations (selling shares bought with unsettled funds before those funds "
          + "settle): active ones (they count toward restrictions: 3 → settled cash only, 4 → liquidation only, for "
          + "90 days), expired ones, and the current restriction.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Good-faith violations", readOnlyHint = true,
          destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public ViolationsDto get_violations(McpTransportContext ctx) {
    long accountId = McpArgs.accountId(accounts, McpArgs.caller(ctx));
    AccountDto account = accounts.account(accountId);
    Instant now = time.now();
    List<ViolationDto> active = new ArrayList<>();
    List<ViolationDto> expired = new ArrayList<>();
    for (ViolationDto v : accounts.violations(accountId)) {
      (v.expiresAt() != null && v.expiresAt().isAfter(now) ? active : expired).add(v);
    }
    return new ViolationsDto(account.restriction(), account.restrictionUntil(), active, expired);
  }

  @McpTool(name = "get_runs", title = "Account runs",
      description = "Every run of your account (a reset closes a run and opens the next): opening and closing time, "
          + "starting cash, final equity, net contributions, return and P&L.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Account runs", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public RunsDto get_runs(McpTransportContext ctx) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    return new RunsDto(accounts.runs(caller.playerId()));
  }

  @McpTool(name = "get_portfolio_history", title = "Portfolio history",
      description = "Your equity curve for a range: points with equity, net contributions, P&L, unit value and the "
          + "SPY benchmark index, the cash flows in the range, and a summary (time-weighted return, P&L, excess "
          + "return vs SPY, max drawdown).",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Portfolio history", readOnlyHint = true,
          destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public PortfolioHistoryDto get_portfolio_history(McpTransportContext ctx,
      @McpToolParam(description = "Range") HistoryRange range) {
    if (range == null) {
      throw ApiException.validation("range", "range is required.");
    }
    return performance.history(McpArgs.accountId(accounts, McpArgs.caller(ctx)), range);
  }
}
