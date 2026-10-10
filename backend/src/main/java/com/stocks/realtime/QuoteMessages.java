package com.stocks.realtime;

import com.stocks.common.domain.SessionType;
import com.stocks.common.error.ApiException;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.ExtendedLine;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.dto.BarDto;
import com.stocks.contract.marketdata.dto.ExtendedLineDto;
import com.stocks.contract.marketdata.dto.QuoteDto;
import com.stocks.contract.marketdata.dto.TickDto;
import com.stocks.contract.marketdata.model.Bar;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * {@code QuoteView} → {@code QuoteDto} / {@code TickDto} and {@code Bar} → {@code BarDto}, exactly as §18.3's mapper
 * table defines them ({@code ageMs = now − asOf}, null when {@code asOf} is null; {@code session} = the calendar's
 * session now; {@code extended.session} PRE or POST). Used by the STOMP fan-out (§19) and the MCP market tools.
 *
 * <p>§18.3 assigns these mappings to WS2's {@code QuoteDtoMapper}/{@code BarDtoMapper} (package
 * {@code marketdata.mapper}), which do not exist on this branch. This class implements the same contract against the
 * {@code contract.marketdata} types only; when WS2's mappers land, delegate to them here (one mapping, one shape).
 */
@Component
public class QuoteMessages {

  private final TimeSource time;
  private final MarketCalendar calendar;

  public QuoteMessages(TimeSource time, MarketCalendar calendar) {
    this.time = time;
    this.calendar = calendar;
  }

  /** The session now ({@code CLOSED} while the calendar is still a Day-0 stub). */
  public SessionType sessionNow() {
    return session(time.now());
  }

  SessionType session(Instant now) {
    try {
      SessionType type = calendar.stateAt(now).type();
      return type == null ? SessionType.CLOSED : type;
    } catch (ApiException e) {
      return SessionType.CLOSED;
    }
  }

  public QuoteDto quote(QuoteView v) {
    Instant now = time.now();
    return quote(v, now, session(now));
  }

  /** The DTO of {@code v} as of {@code now} in {@code session} (callers mapping many quotes compute both once). */
  public QuoteDto quote(QuoteView v, Instant now, SessionType session) {
    Long ageMs = v.asOf() == null ? null : Math.max(0, Duration.between(v.asOf(), now).toMillis());
    return new QuoteDto(v.symbol(), v.bid(), v.bidSize(), v.ask(), v.askSize(), v.last(), v.lastSize(), v.lastAt(),
        v.prevClose(), v.change(), v.changePct(), v.dayOpen(), v.dayHigh(), v.dayLow(), v.volume(),
        v.volumeIsPartial(), extended(v.extended()), v.asOf(), ageMs, v.quality(), v.source(), v.halted(), session);
  }

  /** §19 {@code TickDto}: the projection of a quote that watchlist rows need. */
  public TickDto tick(QuoteView v) {
    ExtendedLine x = v.extended();
    return new TickDto(v.symbol(), v.last(), v.change(), v.changePct(),
        x == null ? null : new TickDto.TickExtendedDto(x.price(), x.changePct()), v.asOf(), v.quality());
  }

  public static ExtendedLineDto extended(ExtendedLine x) {
    if (x == null || (x.session() != SessionType.PRE && x.session() != SessionType.POST)) {
      return null;
    }
    return new ExtendedLineDto(x.session(), x.price(), x.change(), x.changePct(), x.at());
  }

  /** §18.3 {@code Bar}: {@code t = start}, {@code o/h/l/c}, {@code v = volume}, {@code vw = vwap}. */
  public static BarDto bar(Bar b) {
    return new BarDto(b.start(), b.open(), b.high(), b.low(), b.close(), b.volume(), b.vwap());
  }
}
