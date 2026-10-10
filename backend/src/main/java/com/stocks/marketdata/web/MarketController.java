package com.stocks.marketdata.web;

import com.stocks.common.domain.MoverList;
import com.stocks.common.error.ApiException;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.dto.ClockDto;
import com.stocks.contract.marketdata.dto.MoversDto;
import com.stocks.contract.marketdata.dto.QuoteDto;
import com.stocks.contract.marketdata.dto.TradingDayDto;
import com.stocks.marketdata.mapper.ClockDtoMapper;
import com.stocks.marketdata.mapper.MoversDtoMapper;
import com.stocks.marketdata.mapper.QuoteDtoMapper;
import com.stocks.marketdata.mapper.TradingDayDtoMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §18.2 market endpoints: clock, calendar, index quotes and movers ({@code market:read}). */
@RestController
@RequestMapping("/api/v1/market")
@Tag(name = "market", description = "Market clock, calendar, indices and movers (§18.2)")
public class MarketController {

  static final List<String> INDICES = List.of("SPY", "QQQ", "DIA", "IWM");
  static final int MAX_CALENDAR_DAYS = 400;
  static final int MAX_MOVERS = 50;

  private final MarketDataService marketData;
  private final MarketCalendar calendar;
  private final TimeSource time;
  private final ClockDtoMapper clock;
  private final QuoteDtoMapper quotes;
  private final MoversDtoMapper movers;

  public MarketController(MarketDataService marketData, MarketCalendar calendar, TimeSource time, ClockDtoMapper clock,
                          QuoteDtoMapper quotes, MoversDtoMapper movers) {
    this.marketData = marketData;
    this.calendar = calendar;
    this.time = time;
    this.clock = clock;
    this.quotes = quotes;
    this.movers = movers;
  }

  @GetMapping("/clock")
  @Operation(summary = "The market clock",
      description = "Session now (CLOSED/PRE/REGULAR/POST, America/New_York), the next session and regular open and "
          + "close, upcoming holidays and early closes, and the data feed's profile, quality and health.")
  public ClockDto clock() {
    return clock.clock();
  }

  @GetMapping("/calendar")
  @Operation(summary = "Trading days in a date range",
      description = "Only trading days are listed (holidays and weekends are absent). Defaults: from = today (ET), "
          + "to = from + 30 days; at most 400 days. Errors: VALIDATION_FAILED.")
  public List<TradingDayDto> calendar(
      @Parameter(description = "First ET date (inclusive)") @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @Parameter(description = "Last ET date (inclusive)") @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    LocalDate f = from != null ? from : Et.date(time.now());
    LocalDate t = to != null ? to : f.plusDays(30);
    if (t.isBefore(f)) {
      throw ApiException.validation("to", "to must be on or after from");
    }
    if (ChronoUnit.DAYS.between(f, t) > MAX_CALENDAR_DAYS) {
      throw ApiException.validation("to", "at most " + MAX_CALENDAR_DAYS + " days");
    }
    List<TradingDayDto> out = new ArrayList<>();
    for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) {
      calendar.day(d).ifPresent(td -> out.add(TradingDayDtoMapper.toDto(td)));
    }
    return out;
  }

  @GetMapping("/indices")
  @Operation(summary = "Index ETF quotes", description = "Quotes for SPY, QQQ, DIA and IWM.")
  public List<QuoteDto> indices() {
    return quotes.toDtos(marketData.quotes(INDICES).values());
  }

  @GetMapping("/movers")
  @Operation(summary = "Top movers",
      description = "GAINERS, LOSERS or ACTIVES (most active by volume), up to 50. changePct is a fraction "
          + "(0.0123 = +1.23%).")
  public MoversDto movers(
      @Parameter(description = "GAINERS (default), LOSERS or ACTIVES") @RequestParam(defaultValue = "GAINERS")
      MoverList list,
      @Parameter(description = "1–50, default 10") @RequestParam(defaultValue = "10") int limit) {
    if (limit < 1 || limit > MAX_MOVERS) {
      throw ApiException.validation("limit", "limit must be 1.." + MAX_MOVERS);
    }
    return movers.toDto(marketData.movers(list, limit));
  }
}
