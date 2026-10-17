package com.stocks.marketdata.mapper;

import com.stocks.calendar.NyseCalendarGenerator;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import com.stocks.common.error.ApiException;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.marketdata.dto.ClockDto;
import com.stocks.marketdata.config.MarketDataRuntime;
import com.stocks.marketdata.core.MarketFeed;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The market clock (§18.3 {@code Clock}; {@code GET /market/clock}, MCP {@code get_market_clock}): the session now and
 * next, the next regular open and close, upcoming holidays and early closes (120 days ahead), and the feed's profile,
 * nominal quality and health ({@code LIVE}/{@code DEGRADED}/{@code DOWN}, §15.9).
 */
@Component
public class ClockDtoMapper {

  static final int LOOKAHEAD_DAYS = 120;
  static final int MAX_LISTED = 8;

  private final TimeSource time;
  private final MarketCalendar calendar;
  private final MarketDataRuntime runtime;
  private final BooleanSupplier cryptoEnabled;

  public ClockDtoMapper(TimeSource time, MarketCalendar calendar, MarketDataRuntime runtime) {
    this(time, calendar, runtime, () -> true);
  }

  @Autowired
  public ClockDtoMapper(TimeSource time, MarketCalendar calendar, MarketDataRuntime runtime,
                        SettingsService settings) {
    this(time, calendar, runtime, () -> {
      try {
        return settings.cryptoEnabled();
      } catch (ApiException e) {
        return true;                                 // the §23.4 default while settings are a Day-0 stub
      }
    });
  }

  ClockDtoMapper(TimeSource time, MarketCalendar calendar, MarketDataRuntime runtime, BooleanSupplier cryptoEnabled) {
    this.time = time;
    this.calendar = calendar;
    this.runtime = runtime;
    this.cryptoEnabled = cryptoEnabled;
  }

  public ClockDto clock() {
    return clock(time.now());
  }

  public ClockDto clock(Instant now) {
    SessionState s = calendar.stateAt(now);
    LocalDate today = Et.date(now);
    TradingDay nextOpenDay = nextRegularOpenDay(now);
    Instant nextRegularOpen = nextOpenDay == null ? null : nextOpenDay.rthOpenAt();
    Instant nextRegularClose;
    Optional<TradingDay> current = s.tradingDate() == null ? Optional.empty() : calendar.day(s.tradingDate());
    if (s.type() == SessionType.REGULAR && current.isPresent()) {
      nextRegularClose = current.get().rthCloseAt();
    } else {
      nextRegularClose = nextOpenDay == null ? null : nextOpenDay.rthCloseAt();
    }
    boolean earlyClose = current.map(TradingDay::earlyClose)
        .orElse(nextOpenDay != null && nextOpenDay.earlyClose());
    List<ClockDto.ClockHolidayDto> holidays = new ArrayList<>();
    List<LocalDate> earlyCloses = new ArrayList<>();
    for (int i = 0; i < LOOKAHEAD_DAYS; i++) {
      LocalDate d = today.plusDays(i);
      Optional<TradingDay> td = calendar.day(d);
      boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
      if (td.isEmpty() && !weekend && holidays.size() < MAX_LISTED) {
        holidays.add(new ClockDto.ClockHolidayDto(d, NyseCalendarGenerator.holidayName(d).orElse("Market closed")));
      }
      if (td.isPresent() && td.get().earlyClose() && earlyCloses.size() < MAX_LISTED) {
        earlyCloses.add(d);
      }
    }
    MarketFeed feed = runtime.feed();
    MarketFeed.Health health = feed.health();
    DataQuality quality = health == MarketFeed.Health.DOWN ? DataQuality.NONE : feed.nominalQuality();
    ClockDto.ClockFeedDto feedDto = new ClockDto.ClockFeedDto(runtime.props().profile(), quality,
        ClockDto.FeedStatus.valueOf(health.name()), feed.liveWindowEt());
    String nowEt = Et.zoned(now).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    return new ClockDto(now, nowEt, s.type(), s.tradingDate(), s.type() == SessionType.CLOSED ? null : s.endsAt(),
        s.nextType(), s.nextStartsAt(), nextRegularOpen, nextRegularClose, earlyClose, holidays, earlyCloses, feedDto,
        ClockDto.ClockCryptoDto.of(cryptoEnabled.getAsBoolean()));
  }

  private TradingDay nextRegularOpenDay(Instant now) {
    LocalDate d = Et.date(now);
    for (int i = 0; i < 30; i++, d = d.plusDays(1)) {
      Optional<TradingDay> td = calendar.day(d);
      if (td.isPresent() && td.get().rthOpenAt().isAfter(now)) {
        return td.get();
      }
    }
    return null;
  }
}
