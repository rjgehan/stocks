package com.stocks.marketdata.spi;

import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A provider whose market is a pure function of time (the SIM, §15.7): every question can be answered for any
 * instant without a network call. {@code MarketDataServiceImpl} reads it directly at {@code TimeSource.now()}, which
 * makes quotes, executable quotes, prints and marks deterministic under a fixed test clock (§12.10).
 */
public interface PointInTimeMarket {

  /** Session statistics of a trading day's regular session so far. */
  record DayStats(BigDecimal open, BigDecimal high, BigDecimal low, long volume) {}

  boolean knows(String symbol);

  /** The quote in force at {@code t} (the last update at or before it; frozen outside sessions). */
  Optional<Quote> quoteAt(String symbol, Instant t);

  /** The last print at or before {@code t}. */
  Optional<Trade> lastTradeAt(String symbol, Instant t);

  /** Prints with {@code fromExclusive < ts <= toInclusive}, oldest first, at most {@code max}. */
  List<Trade> tradesBetween(String symbol, Instant fromExclusive, Instant toInclusive, int max);

  /** The official (regular-session) close of a trading day, raw (as traded that day). */
  Optional<BigDecimal> officialClose(String symbol, LocalDate tradeDate);

  /** The mid price at {@code t} (raw), rounded to the tick. */
  Optional<BigDecimal> priceAt(String symbol, Instant t);

  /** Regular-session open/high/low/volume of {@code tradeDate} up to {@code upTo}; empty before the open. */
  Optional<DayStats> dayStats(String symbol, LocalDate tradeDate, Instant upTo);

  /** The 1Min bar starting at {@code minuteStart} built from prints up to {@code upTo}; empty without prints. */
  Optional<Bar> minuteBar(String symbol, Instant minuteStart, Instant upTo);

  /** Until when the feed vouches for its data ({@code now}, or the start of a blackout). */
  Instant confirmedAt(String symbol, Instant now);

  boolean halted(String symbol, Instant t);
}
