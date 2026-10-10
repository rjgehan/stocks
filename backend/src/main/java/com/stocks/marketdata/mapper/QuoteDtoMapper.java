package com.stocks.marketdata.mapper;

import com.stocks.common.domain.SessionType;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.ExtendedLine;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.dto.ExtendedLineDto;
import com.stocks.contract.marketdata.dto.QuoteDto;
import com.stocks.contract.marketdata.dto.TickDto;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code QuoteView → QuoteDto} (§18.3 mapper table), shared by {@code /quotes}, {@code /market/indices},
 * {@code /topic/quote.*}, {@code /topic/tick.*} (a projection) and MCP {@code get_quotes}: {@code ageMs = now − asOf}
 * (null without {@code asOf}, never negative), {@code session} = the session now, {@code extended.session} PRE or POST.
 */
@Component
public class QuoteDtoMapper {

  private final TimeSource time;
  private final MarketCalendar calendar;

  public QuoteDtoMapper(TimeSource time, MarketCalendar calendar) {
    this.time = time;
    this.calendar = calendar;
  }

  public QuoteDto toDto(QuoteView v) {
    Instant now = time.now();
    return toDto(v, now, calendar.stateAt(now).type());
  }

  public List<QuoteDto> toDtos(Collection<QuoteView> views) {
    Instant now = time.now();
    SessionType session = calendar.stateAt(now).type();
    List<QuoteDto> out = new ArrayList<>(views.size());
    views.forEach(v -> out.add(toDto(v, now, session)));
    return out;
  }

  public static QuoteDto toDto(QuoteView v, Instant now, SessionType session) {
    Long ageMs = v.asOf() == null ? null : Math.max(0L, Duration.between(v.asOf(), now).toMillis());
    return new QuoteDto(v.symbol(), v.bid(), v.bidSize(), v.ask(), v.askSize(), v.last(), v.lastSize(), v.lastAt(),
        v.prevClose(), v.change(), v.changePct(), v.dayOpen(), v.dayHigh(), v.dayLow(), v.volume(),
        v.volumeIsPartial(), extended(v.extended()), v.asOf(), ageMs, v.quality(), v.source(), v.halted(), session);
  }

  static ExtendedLineDto extended(ExtendedLine e) {
    if (e == null || (e.session() != SessionType.PRE && e.session() != SessionType.POST)) {
      return null;
    }
    return new ExtendedLineDto(e.session(), e.price(), e.change(), e.changePct(), e.at());
  }

  /** The §19 {@code /topic/tick.{SYM}} projection. */
  public static TickDto toTick(QuoteView v) {
    ExtendedLine e = v.extended();
    TickDto.TickExtendedDto ext = e == null ? null : new TickDto.TickExtendedDto(e.price(), e.changePct());
    return new TickDto(v.symbol(), v.last(), v.change(), v.changePct(), ext, v.asOf(), v.quality());
  }
}
