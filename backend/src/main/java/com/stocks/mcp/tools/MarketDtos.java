package com.stocks.mcp.tools;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.MarketDataStatus;
import com.stocks.contract.marketdata.ProviderStatus;
import com.stocks.contract.marketdata.dto.AssetDto;
import com.stocks.contract.marketdata.dto.AssetSummaryDto;
import com.stocks.contract.marketdata.dto.ClockDto;
import com.stocks.contract.marketdata.dto.FundamentalsDto;
import com.stocks.contract.marketdata.dto.MoversDto;
import com.stocks.contract.marketdata.dto.NewsItemDto;
import com.stocks.contract.marketdata.dto.TradingDayDto;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.contract.marketdata.model.Mover;
import com.stocks.contract.marketdata.model.Movers;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.mcp.Untrusted;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Market model → §18.3 DTO mappings for the MCP market tools, following §18.3's mapper table ({@code TradingDay.tradeDate
 * → date}, {@code week52High/Low} from {@code Fundamentals} onto {@code Asset}, movers' names from the asset, news text
 * in {@code untrusted}). §18.3 assigns these mappers to WS2 ({@code marketdata.mapper}); they do not exist on this
 * branch, so this class implements the same contract against {@code contract.*} types only. When WS2's mappers and
 * {@code GET /market/clock} land, delegate to them here so REST and MCP share one shape.
 */
final class MarketDtos {

  static final DateTimeFormatter NOW_ET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  /** How far ahead {@code Clock.upcomingHolidays}/{@code upcomingEarlyCloses} look. */
  static final int LOOK_AHEAD_DAYS = 120;

  private MarketDtos() {}

  static TradingDayDto tradingDay(TradingDay d) {
    return new TradingDayDto(d.tradeDate(), d.preOpenAt(), d.rthOpenAt(), d.rthCloseAt(), d.postCloseAt(),
        d.settlementDate(), d.earlyClose());
  }

  static AssetSummaryDto summary(Asset a) {
    return new AssetSummaryDto(a.symbol(), a.name(), a.exchange(), a.type(), a.tradable(), a.fractionable());
  }

  /** {@code onHold}: a corporate-action hold (§14) makes the asset untradable until the admin resolves it. */
  static AssetDto asset(Asset a, Optional<Fundamentals> f, boolean onHold) {
    return new AssetDto(a.symbol(), a.name(), a.exchange(), a.type(), a.tradable() && !onHold, a.fractionable(),
        a.shortable(), a.marginable(), a.tier(),
        a.active() ? AssetDto.AssetStatus.ACTIVE : AssetDto.AssetStatus.INACTIVE,
        f.map(Fundamentals::week52High).orElse(null), f.map(Fundamentals::week52Low).orElse(null),
        logoUrl(a.symbol()), f.map(MarketDtos::fundamentals).orElse(null));
  }

  static String logoUrl(String symbol) {
    return "/api/v1/assets/" + symbol + "/logo";
  }

  static FundamentalsDto fundamentals(Fundamentals f) {
    return new FundamentalsDto(f.marketCap(), f.peTtm(), f.epsTtm(), f.dividendYield(), f.beta(),
        f.sharesOutstanding(), f.industry(), f.website(), f.source());
  }

  static MoversDto movers(Movers m, Function<String, String> nameOf) {
    List<MoversDto.MoverItemDto> items = new ArrayList<>();
    for (Mover x : m.items()) {
      items.add(new MoversDto.MoverItemDto(x.symbol(), nameOf.apply(x.symbol()), x.price(), x.change(),
          x.changePct(), x.volume()));
    }
    return new MoversDto(m.list(), m.asOf(), m.source(), items);
  }

  static NewsItemDto news(NewsItem n) {
    return new NewsItemDto(n.id(), n.publishedAt(), n.source(), n.url(), n.symbols() == null ? List.of() : n.symbols(),
        n.imageUrl(), new NewsItemDto.UntrustedNewsDto(Untrusted.news(n.headline()), Untrusted.news(n.summary())));
  }

  // ------------------------------------------------------------------------------------------- clock

  static ClockDto clock(Instant now, MarketCalendar calendar, MarketDataStatus status) {
    SessionState state = calendar.stateAt(now);
    LocalDate todayEt = LocalDate.ofInstant(now, TimeSource.ET);
    boolean earlyClose = state.tradingDate() != null
        && calendar.day(state.tradingDate()).map(TradingDay::earlyClose).orElse(false);
    Instant nextOpen = nextAfter(now, todayEt, calendar, TradingDay::rthOpenAt);
    Instant nextClose = nextAfter(now, todayEt, calendar, TradingDay::rthCloseAt);
    List<ClockDto.ClockHolidayDto> holidays = new ArrayList<>();
    List<LocalDate> earlyCloses = new ArrayList<>();
    for (int i = 0; i <= LOOK_AHEAD_DAYS; i++) {
      LocalDate d = todayEt.plusDays(i);
      boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
      Optional<TradingDay> day = calendar.day(d);
      if (day.isEmpty() && !weekend) {
        holidays.add(new ClockDto.ClockHolidayDto(d, holidayName(d)));
      } else if (day.isPresent() && day.get().earlyClose()) {
        earlyCloses.add(d);
      }
    }
    String nowEt = ZonedDateTime.ofInstant(now.truncatedTo(ChronoUnit.SECONDS), TimeSource.ET).format(NOW_ET);
    return new ClockDto(now, nowEt, state.type(), state.tradingDate(), state.endsAt(), state.nextType(),
        state.nextStartsAt(), nextOpen, nextClose, earlyClose, holidays, earlyCloses, feed(status));
  }

  /** The first {@code pick(day)} strictly after {@code now}, looking at today and the following trading days. */
  private static Instant nextAfter(Instant now, LocalDate todayEt, MarketCalendar calendar,
                                   Function<TradingDay, Instant> pick) {
    LocalDate d = calendar.isTradingDay(todayEt) ? todayEt : calendar.nextTradingDay(todayEt);
    for (int i = 0; i < 10; i++) {
      Optional<TradingDay> day = calendar.day(d);
      if (day.isPresent() && pick.apply(day.get()).isAfter(now)) {
        return pick.apply(day.get());
      }
      d = calendar.nextTradingDay(d);
    }
    return null;
  }

  static ClockDto.ClockFeedDto feed(MarketDataStatus status) {
    String profile = status == null || status.profile() == null ? "none" : status.profile();
    String p = profile.toLowerCase(Locale.ROOT);
    boolean simulated = p.startsWith("sim");
    boolean sip = p.contains("sip");
    DataQuality quality = simulated ? DataQuality.SIMULATED : p.equals("none") ? DataQuality.NONE
        : sip ? DataQuality.REALTIME_CONSOLIDATED : DataQuality.REALTIME_SINGLE_VENUE;
    ClockDto.FeedStatus feedStatus;
    if (p.equals("none")) {
      feedStatus = ClockDto.FeedStatus.DOWN;
    } else if (status.providers() == null || status.providers().isEmpty()) {
      feedStatus = ClockDto.FeedStatus.LIVE;
    } else {
      int down = 0;
      int degraded = 0;
      for (ProviderStatus ps : status.providers()) {
        String s = ps.state() == null ? "" : ps.state().toUpperCase(Locale.ROOT);
        if (s.contains("DOWN") || s.contains("ERROR") || s.contains("FAIL") || s.contains("DISCONNECTED")) {
          down++;
        } else if (s.contains("DEGRADED") || s.contains("RECONNECT") || s.contains("BACKOFF")
            || s.contains("CONNECTING")) {
          degraded++;
        }
      }
      feedStatus = down == status.providers().size() ? ClockDto.FeedStatus.DOWN
          : down + degraded > 0 ? ClockDto.FeedStatus.DEGRADED : ClockDto.FeedStatus.LIVE;
    }
    String iexWindow = simulated || sip || p.equals("none") ? null : "08:00–17:00";
    return new ClockDto.ClockFeedDto(profile, quality, feedStatus, iexWindow);
  }

  /** The NYSE holiday a weekday closure is (by rule, incl. observed dates); "Market closed" for anything else. */
  static String holidayName(LocalDate d) {
    int year = d.getYear();
    if (observed(LocalDate.of(year, Month.JANUARY, 1)).equals(d)
        || observed(LocalDate.of(year + 1, Month.JANUARY, 1)).equals(d)) {
      return "New Year's Day";
    }
    if (nth(year, Month.JANUARY, DayOfWeek.MONDAY, 3).equals(d)) {
      return "Martin Luther King Jr. Day";
    }
    if (nth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3).equals(d)) {
      return "Washington's Birthday";
    }
    if (easter(year).minusDays(2).equals(d)) {
      return "Good Friday";
    }
    if (LocalDate.of(year, Month.MAY, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).equals(d)) {
      return "Memorial Day";
    }
    if (observed(LocalDate.of(year, Month.JUNE, 19)).equals(d)) {
      return "Juneteenth";
    }
    if (observed(LocalDate.of(year, Month.JULY, 4)).equals(d)) {
      return "Independence Day";
    }
    if (nth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1).equals(d)) {
      return "Labor Day";
    }
    if (nth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4).equals(d)) {
      return "Thanksgiving Day";
    }
    if (observed(LocalDate.of(year, Month.DECEMBER, 25)).equals(d)) {
      return "Christmas Day";
    }
    return "Market closed";
  }

  private static LocalDate observed(LocalDate holiday) {
    return switch (holiday.getDayOfWeek()) {
      case SATURDAY -> holiday.minusDays(1);
      case SUNDAY -> holiday.plusDays(1);
      default -> holiday;
    };
  }

  private static LocalDate nth(int year, Month month, DayOfWeek dow, int n) {
    return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
  }

  /** Gregorian Easter Sunday (anonymous Gregorian algorithm). */
  static LocalDate easter(int year) {
    int a = year % 19;
    int b = year / 100;
    int c = year % 100;
    int d = b / 4;
    int e = b % 4;
    int f = (b + 8) / 25;
    int g = (b - f + 1) / 3;
    int h = (19 * a + b - d - g + 15) % 30;
    int i = c / 4;
    int k = c % 4;
    int l = (32 + 2 * e + 2 * i - h - k) % 7;
    int m = (a + 11 * h + 22 * l) / 451;
    int month = (h + l - 7 * m + 114) / 31;
    int day = ((h + l - 7 * m + 114) % 31) + 1;
    return LocalDate.of(year, month, day);
  }
}
