package com.stocks.portfolio.web;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.portfolio.PortfolioRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §18.2 {@code GET /account/history} ({@code account:read}): the caller's equity curve, run-relative (§16.3). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "account", description = "The caller's account")
public class PerformanceController {

  private final PerformanceQueries performance;
  private final PortfolioRepository accounts;

  public PerformanceController(PerformanceQueries performance, PortfolioRepository accounts) {
    this.performance = performance;
    this.accounts = accounts;
  }

  @GetMapping("/account/history")
  @Operation(summary = "Equity curve of the active run", description = "Points from the range's anchor (the run's "
      + "EOD, or its opening) through EOD snapshots (1D: 5-minute samples) to a live point. pnl = P&L since the "
      + "anchor; return = time-weighted (unit value), so deposits never move it.")
  public PortfolioHistoryDto getAccountHistory(
      @Parameter(description = "1D, 1W, 1M (default), 3M, YTD, 1Y or ALL")
      @RequestParam(name = "range", required = false) HistoryRange range) {
    long playerId = CurrentPlayer.require().playerId();
    long accountId = accounts.activeAccountId(playerId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "You have no active account yet."));
    return performance.history(accountId, range == null ? HistoryRange.M1 : range);
  }
}
