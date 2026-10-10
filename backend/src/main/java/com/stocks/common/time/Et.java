package com.stocks.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * America/New_York helpers. Outside {@code com.stocks.calendar}, ET conversion happens only here (§6.4). Trading-day
 * questions (is it a trading day, when does it close) go through {@code MarketCalendar}, never through this class.
 */
public final class Et {

  public static final ZoneId ZONE = TimeSource.ET;

  private Et() {}

  /** The ET calendar date of an instant. */
  public static LocalDate date(Instant instant) {
    return LocalDate.ofInstant(instant, ZONE);
  }

  /** The ET wall-clock time of an instant. */
  public static LocalTime time(Instant instant) {
    return LocalTime.ofInstant(instant, ZONE);
  }

  public static ZonedDateTime zoned(Instant instant) {
    return instant.atZone(ZONE);
  }

  /**
   * The instant of an ET wall-clock time on a date. In the spring-forward gap the time moves forward by the gap
   * length; in the fall-back overlap the earlier offset (EDT) wins, as {@link ZonedDateTime#of} does.
   */
  public static Instant at(LocalDate date, LocalTime time) {
    return ZonedDateTime.of(LocalDateTime.of(date, time), ZONE).toInstant();
  }

  /** 00:00 ET on {@code date}. */
  public static Instant startOfDay(LocalDate date) {
    return date.atStartOfDay(ZONE).toInstant();
  }
}
