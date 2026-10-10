package com.stocks.calendar;

import com.stocks.common.time.Et;
import com.stocks.contract.calendar.TradingDay;
import java.time.LocalDate;
import java.time.LocalTime;

/** Conversions between ET wall-clock rows (CSV, Alpaca, generator) and {@link TradingDay}. */
final class TradingDays {

  static final String BUNDLED = "bundled";
  static final String ALPACA = "alpaca";
  static final String OVERRIDE = "override";
  static final String GENERATED = "generated";
  static final String ALWAYS_OPEN = "always-open";

  private TradingDays() {}

  static TradingDay of(LocalDate date, LocalTime preOpen, LocalTime rthOpen, LocalTime rthClose, LocalTime postClose,
                       LocalDate settlementDate, boolean earlyClose, String source) {
    TradingDay d = new TradingDay(date, Et.at(date, preOpen), Et.at(date, rthOpen), Et.at(date, rthClose),
        Et.at(date, postClose), settlementDate, earlyClose, source);
    check(d);
    return d;
  }

  static TradingDay of(NyseCalendarGenerator.Row r, String source) {
    return of(r.date(), r.preOpen(), r.rthOpen(), r.rthClose(), r.postClose(), r.settlementDate(), r.earlyClose(),
        source);
  }

  /** The {@code trading_day} CHECKs (§8.1), enforced before anything reaches the database or the calendar. */
  static void check(TradingDay d) {
    if (!(d.preOpenAt().isBefore(d.rthOpenAt()) && d.rthOpenAt().isBefore(d.rthCloseAt())
        && !d.rthCloseAt().isAfter(d.postCloseAt()))) {
      throw new IllegalArgumentException("Session times out of order on " + d.tradeDate() + ": " + d);
    }
    if (!d.settlementDate().isAfter(d.tradeDate())) {
      throw new IllegalArgumentException("Settlement date must be after the trade date: " + d);
    }
  }

  /** DB {@code source} column value ({@code ALPACA}, {@code BUNDLED}, {@code OVERRIDE}). */
  static String dbSource(String source) {
    return switch (source) {
      case ALPACA -> "ALPACA";
      case OVERRIDE -> "OVERRIDE";
      default -> "BUNDLED";
    };
  }

  static String fromDbSource(String db) {
    return switch (db) {
      case "ALPACA" -> ALPACA;
      case "OVERRIDE" -> OVERRIDE;
      default -> BUNDLED;
    };
  }
}
