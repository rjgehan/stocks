package com.stocks.calendar;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.CalendarAdmin;
import com.stocks.contract.calendar.CalendarOverride;
import com.stocks.contract.calendar.TradingDay;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin calendar overrides (§9.1 source 1, §21.1 {@code /admin/calendar/overrides}): an ad-hoc closure or a shortened
 * session. Saving reloads the calendar and asks the job scheduler to re-plan (so today's "16:20" jobs move), through
 * the {@code onChange} hook wired by {@code CalendarConfiguration}.
 */
public class CalendarAdminImpl implements CalendarAdmin {

  private static final Logger log = LoggerFactory.getLogger(CalendarAdminImpl.class);
  static final int MAX_REASON = 200;

  private final CalendarRepository repository;
  private final DbMarketCalendar calendar;
  private final TimeSource time;
  private final Runnable onChange;

  public CalendarAdminImpl(CalendarRepository repository, DbMarketCalendar calendar, TimeSource time,
                           Runnable onChange) {
    this.repository = repository;
    this.calendar = calendar;
    this.time = time;
    this.onChange = onChange == null ? () -> { } : onChange;
  }

  @Override
  public List<CalendarOverride> overrides(LocalDate fromInclusive, LocalDate toInclusive) {
    if (fromInclusive == null || toInclusive == null || toInclusive.isBefore(fromInclusive)) {
      throw ApiException.validation("to", "to must be on or after from");
    }
    return repository.overrides(fromInclusive, toInclusive);
  }

  @Override
  public CalendarOverride putOverride(LocalDate date, boolean closed, Instant rthCloseAtOrNull,
                                      Instant postCloseAtOrNull, String reason, long actorPlayerId) {
    if (date == null) {
      throw ApiException.validation("date", "date is required");
    }
    String why = reason == null ? "" : reason.strip();
    if (why.isEmpty() || why.length() > MAX_REASON) {
      throw ApiException.validation("reason", "reason is required (1-" + MAX_REASON + " characters)");
    }
    Optional<TradingDay> base = calendar.baseDay(date);
    if (base.isEmpty()) {
      throw ApiException.validation("date", date + " is not a trading day");
    }
    if (closed) {
      if (rthCloseAtOrNull != null || postCloseAtOrNull != null) {
        throw ApiException.validation("closed", "a closed day takes no close times");
      }
    } else {
      validateTimes(base.get(), rthCloseAtOrNull, postCloseAtOrNull);
    }
    repository.putOverride(date, closed, rthCloseAtOrNull, postCloseAtOrNull, why, actorPlayerId, time.now());
    calendar.reload();
    log.info("Calendar override on {}: closed={} rthClose={} postClose={} ({}) by player {}", date, closed,
        rthCloseAtOrNull, postCloseAtOrNull, why, actorPlayerId);
    onChange.run();
    return repository.override(date).orElseThrow();
  }

  private static void validateTimes(TradingDay base, Instant rthClose, Instant postClose) {
    if (rthClose == null && postClose == null) {
      throw ApiException.validation("rthCloseAt", "an open day needs rthCloseAt and/or postCloseAt");
    }
    Instant endOfDay = Et.at(base.tradeDate(), LocalTime.of(23, 59));
    if (rthClose != null && (!rthClose.isAfter(base.rthOpenAt()) || rthClose.isAfter(base.postCloseAt()))) {
      throw ApiException.validation("rthCloseAt", "rthCloseAt must be after the 09:30 open and not after the "
          + "normal post-close");
    }
    if (postClose != null) {
      Instant effectiveRthClose = rthClose != null ? rthClose : base.rthCloseAt();
      if (postClose.isBefore(effectiveRthClose) || postClose.isAfter(endOfDay)) {
        throw ApiException.validation("postCloseAt", "postCloseAt must be between the RTH close and 23:59 ET");
      }
    }
  }

  @Override
  public void deleteOverride(LocalDate date, long actorPlayerId) {
    if (!repository.deleteOverride(date)) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "No calendar override on " + date + ".");
    }
    calendar.reload();
    log.info("Calendar override on {} deleted by player {}", date, actorPlayerId);
    onChange.run();
  }
}
