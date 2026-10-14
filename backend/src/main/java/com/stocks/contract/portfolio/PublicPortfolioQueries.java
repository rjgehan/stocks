package com.stocks.contract.portfolio;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.CalendarView;
import com.stocks.contract.portfolio.dto.PublicOrderDto;
import com.stocks.contract.portfolio.dto.PublicPortfolioDto;
import com.stocks.contract.trading.ActivityFilter;
import com.stocks.contract.trading.OrderStatusFilter;
import com.stocks.contract.trading.dto.ActivityDto;
import java.time.YearMonth;

/** Public mode (§16.7): any listed player's live portfolio, orders, activity and P&amp;L, for REST and MCP. */
public interface PublicPortfolioQueries {

  /** PLAYER_NOT_FOUND for an unknown or DISABLED player; a player without an active run gets an empty portfolio. */
  PublicPortfolioDto portfolio(String handle);

  /** As portfolio, for any status (the admin player page); PLAYER_NOT_FOUND only for an unknown handle. */
  PublicPortfolioDto portfolioAnyStatus(String handle);

  /** Every stored order of every run, newest first ((received_at, id) cursor), with fills. */
  Page<PublicOrderDto> orders(String handle, OrderStatusFilter status, String symbolOrNull, Cursor cursor, int limit);

  /** The active run's activity, sanitized (no ADMIN_VIEW, no non-system memos, no account ids); empty without a run. */
  Page<ActivityDto> activities(String handle, ActivityFilter filter, Cursor cursor, int limit);

  /** PnlAnalysisQueries.analysis of the active run; ACCOUNT_NOT_FOUND without one. */
  PnlAnalysisDto pnlAnalysis(String handle, HistoryRange range);

  /** PnlAnalysisQueries.calendar of the active run; ACCOUNT_NOT_FOUND without one. */
  PnlCalendarDto pnlCalendar(String handle, CalendarView view, YearMonth monthOrNull, Integer yearOrNull);
}
