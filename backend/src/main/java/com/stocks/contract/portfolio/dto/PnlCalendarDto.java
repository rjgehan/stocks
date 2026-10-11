package com.stocks.contract.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * §18.3 {@code PnlCalendar} (§16.10, Phase 2; {@code GET /account/pnl-calendar}, MCP {@code get_pnl_calendar}): the
 * caller's ACTIVE run as daily ({@code DAY}: one cell per trading day of a month) or monthly ({@code MONTH}: one cell
 * per month of a year) P&amp;L. A day cell is the EOD snapshot's {@code pnl_day}, which never counts deposits or
 * withdrawals ({@code flows} shows them) and never counts a split or rename (equity and U are unchanged by them);
 * today's cell is live until the EOD job writes it.
 */
public record PnlCalendarDto(CalendarView view, LocalDate from, LocalDate to, Instant asOf,
                             List<PnlCalendarCellDto> cells, PnlCalendarTotalDto total) {

  /** {@code DAY} (a month of trading days) or {@code MONTH} (a year of months). */
  public enum CalendarView { DAY, MONTH }

  /**
   * One cell: the day, or the month's first day. {@code return} is {@code U_last / U_before − 1} (−1 across a
   * rebase); {@code live}: contains today's not-yet-snapshotted P&amp;L.
   */
  public record PnlCalendarCellDto(LocalDate date, BigDecimal pnl, @JsonProperty("return") BigDecimal ret,
                                   BigDecimal flows, boolean live) {}

  /** Σ of the cells' P&amp;L, and the chained return over all of them. */
  public record PnlCalendarTotalDto(BigDecimal pnl, @JsonProperty("return") BigDecimal ret) {}
}
