package com.stocks.contract.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Backs {@code /admin/calendar/overrides} (§21.1, WS2). */
public interface CalendarAdmin {

  List<CalendarOverride> overrides(LocalDate fromInclusive, LocalDate toInclusive);

  /** Stores the override, reloads the calendar; {@code JobScheduler} re-plans. */
  CalendarOverride putOverride(LocalDate date, boolean closed, Instant rthCloseAtOrNull, Instant postCloseAtOrNull,
                               String reason, long actorPlayerId);

  /** @throws com.stocks.common.error.ApiException {@code NOT_FOUND} if there is no override on that date */
  void deleteOverride(LocalDate date, long actorPlayerId);
}
