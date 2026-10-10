package com.stocks.contract.marketdata.dto;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * §18.3 {@code Snapshot = Quote & {...}}, flattened: the first 23 components are {@code Quote}'s, in order.
 */
public record SnapshotDto(String symbol, BigDecimal bid, Long bidSize, BigDecimal ask, Long askSize, BigDecimal last,
                          Long lastSize, Instant lastAt, BigDecimal prevClose, BigDecimal change, BigDecimal changePct,
                          BigDecimal dayOpen, BigDecimal dayHigh, BigDecimal dayLow, Long volume,
                          boolean volumeIsPartial, ExtendedLineDto extended, Instant asOf, Long ageMs,
                          DataQuality quality, String source, boolean halted, SessionType session,
                          BarDto minuteBar, BarDto dailyBar, BigDecimal week52High, BigDecimal week52Low,
                          BigDecimal marketCap) {

  /** Builds a snapshot from a quote plus the snapshot-only fields. */
  public static SnapshotDto of(QuoteDto q, BarDto minuteBar, BarDto dailyBar, BigDecimal week52High,
                               BigDecimal week52Low, BigDecimal marketCap) {
    return new SnapshotDto(q.symbol(), q.bid(), q.bidSize(), q.ask(), q.askSize(), q.last(), q.lastSize(),
        q.lastAt(), q.prevClose(), q.change(), q.changePct(), q.dayOpen(), q.dayHigh(), q.dayLow(), q.volume(),
        q.volumeIsPartial(), q.extended(), q.asOf(), q.ageMs(), q.quality(), q.source(), q.halted(), q.session(),
        minuteBar, dailyBar, week52High, week52Low, marketCap);
  }
}
