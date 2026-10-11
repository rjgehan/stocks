package com.stocks.contract.portfolio;

import com.stocks.common.domain.HistoryRange;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.CalendarView;
import java.time.YearMonth;

/** Phase 2 P&amp;L analysis of one account's run (§16.10, WS4; run-relative like the account page, §16.3). */
public interface PnlAnalysisQueries {

  /** My Earnings (vs SPY and the extra benchmarks) and P&amp;L by symbol over a history range. */
  PnlAnalysisDto analysis(long accountId, HistoryRange range);

  /**
   * The P&amp;L calendar: {@code DAY} cells of {@code month}, or {@code MONTH} cells of {@code year}; a null month or
   * year means the current ET one.
   */
  PnlCalendarDto calendar(long accountId, CalendarView view, YearMonth month, Integer year);
}
