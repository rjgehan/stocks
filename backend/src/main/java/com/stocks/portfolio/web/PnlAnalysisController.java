package com.stocks.portfolio.web;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.portfolio.PnlAnalysisQueries;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.CalendarView;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.portfolio.PortfolioRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2 P&amp;L analysis (§16.10; §18.2 {@code GET /account/pnl-analysis} and {@code GET /account/pnl-calendar},
 * {@code account:read}): the caller's ACTIVE run, run-relative like {@code GET /account/history}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "account", description = "The caller's account")
public class PnlAnalysisController {

  static final Pattern MONTH = Pattern.compile("^\\d{4}-\\d{2}$");
  static final int MIN_YEAR = 2000;
  static final int MAX_YEAR = 2100;

  private final PnlAnalysisQueries analysis;
  private final PortfolioRepository accounts;

  public PnlAnalysisController(PnlAnalysisQueries analysis, PortfolioRepository accounts) {
    this.analysis = analysis;
    this.accounts = accounts;
  }

  private long activeAccount() {
    long playerId = CurrentPlayer.require().playerId();
    return accounts.activeAccountId(playerId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "You have no active account yet."));
  }

  @GetMapping("/account/pnl-analysis")
  @Operation(summary = "P&L analysis of the active run", description = "My Earnings: your time-weighted return and "
      + "P&L over the range vs SPY and QQQ (total-return indexes), and the P&L by symbol (realized, change of "
      + "unrealized, dividends); the symbols' totals plus `other` (P&L corrections) add up to `pnl`.")
  public PnlAnalysisDto getPnlAnalysis(
      @Parameter(description = "1D, 1W, 1M (default), 3M, YTD, 1Y or ALL")
      @RequestParam(name = "range", required = false) HistoryRange range) {
    return analysis.analysis(activeAccount(), range == null ? HistoryRange.M1 : range);
  }

  @GetMapping("/account/pnl-calendar")
  @Operation(summary = "P&L calendar of the active run", description = "DAY: one cell per trading day of `month` "
      + "(YYYY-MM, default the current ET month); MONTH: one cell per month of `year`. A day's P&L never counts "
      + "deposits or withdrawals (shown as `flows`) nor splits or renames; today's cell is live until the EOD "
      + "snapshot.")
  public PnlCalendarDto getPnlCalendar(
      @Parameter(description = "DAY (default) or MONTH")
      @RequestParam(name = "view", required = false) CalendarView view,
      @Parameter(description = "YYYY-MM (DAY view)")
      @RequestParam(name = "month", required = false) String month,
      @Parameter(description = "2000..2100 (MONTH view)")
      @RequestParam(name = "year", required = false) Integer year) {
    return analysis.calendar(activeAccount(), view == null ? CalendarView.DAY : view, month(month), year(year));
  }

  /** A {@code YYYY-MM} month (2000..2100), or null when absent; {@code VALIDATION_FAILED} otherwise. */
  public static YearMonth month(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    if (!MONTH.matcher(raw.strip()).matches()) {
      throw ApiException.validation("month", "month must be YYYY-MM");
    }
    try {
      YearMonth ym = YearMonth.parse(raw.strip());
      if (ym.getYear() < MIN_YEAR || ym.getYear() > MAX_YEAR) {
        throw ApiException.validation("month", "month must be between " + MIN_YEAR + "-01 and " + MAX_YEAR + "-12");
      }
      return ym;
    } catch (DateTimeParseException e) {
      throw ApiException.validation("month", "month must be YYYY-MM");
    }
  }

  /** A year in 2000..2100, or null; {@code VALIDATION_FAILED} otherwise. */
  public static Integer year(Integer year) {
    if (year != null && (year < MIN_YEAR || year > MAX_YEAR)) {
      throw ApiException.validation("year", "year must be between " + MIN_YEAR + " and " + MAX_YEAR);
    }
    return year;
  }
}
