package com.stocks.contract.marketdata.dto;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * §18.3 {@code Clock} ({@code GET /market/clock}, MCP {@code get_market_clock}). {@code nowEt} is the ET wall-clock
 * ISO-8601 string with offset ({@code 2026-10-09T10:31:02-04:00}).
 */
public record ClockDto(Instant now, String nowEt, SessionType session, LocalDate tradingDate, Instant sessionEndsAt,
                       SessionType nextSession, Instant nextSessionAt, Instant nextRegularOpen,
                       Instant nextRegularClose, boolean earlyClose, List<ClockHolidayDto> upcomingHolidays,
                       List<LocalDate> upcomingEarlyCloses, ClockFeedDto feed, ClockCryptoDto crypto) {

  /** A clock without its crypto member (fixtures). */
  public ClockDto(Instant now, String nowEt, SessionType session, LocalDate tradingDate, Instant sessionEndsAt,
                  SessionType nextSession, Instant nextSessionAt, Instant nextRegularOpen, Instant nextRegularClose,
                  boolean earlyClose, List<ClockHolidayDto> upcomingHolidays, List<LocalDate> upcomingEarlyCloses,
                  ClockFeedDto feed) {
    this(now, nowEt, session, tradingDate, sessionEndsAt, nextSession, nextSessionAt, nextRegularOpen,
        nextRegularClose, earlyClose, upcomingHolidays, upcomingEarlyCloses, feed, null);
  }

  /**
   * §29: crypto pairs trade 24/7 while {@code enabled} (the admin's {@code crypto.enabled}); the session members above
   * describe the US stock market only.
   */
  public record ClockCryptoDto(boolean enabled, List<String> pairs) {

    public static ClockCryptoDto of(boolean enabled) {
      return new ClockCryptoDto(enabled, com.stocks.common.domain.Crypto.symbols());
    }
  }

  public record ClockHolidayDto(LocalDate date, String name) {}

  /** {@code liveIexWindowEt}: e.g. {@code "08:00–17:00"}; null when no live venue applies. */
  public record ClockFeedDto(String profile, DataQuality quality, FeedStatus status, String liveIexWindowEt) {}

  public enum FeedStatus { LIVE, DEGRADED, DOWN }
}
