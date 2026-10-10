package com.stocks.marketdata.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.stocks.common.domain.ChartRange;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.error.ApiException;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.BarRequest;
import com.stocks.contract.marketdata.BarSeries;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chart bars (§15.5 {@code BarService}). Ranges map to a default timeframe and start (1D → 1Min from the pre-open of
 * the current or latest trading day; 5D → 5Min over 5 trading days; 1M → 30Min; 3M/6M/YTD/1Y → 1Day; 5Y → 1Week;
 * ALL → 1Month from 2016-01-01). Live data stitches SIP history older than 16 minutes ({@code feed=sip}) with an IEX
 * tail ({@code feed=iex}, {@code partialVolume=true}) and the forming minute bar from the stream; SIP wins on equal
 * starts. Charts default to split-adjusted bars. Results are cached (1Min 30 s, intraday 5 min, daily+ 1 h, fully past
 * windows 24 h).
 */
public class BarService {

  private static final Logger log = LoggerFactory.getLogger(BarService.class);
  public static final int MAX_LIMIT = 1000;
  static final Duration SIP_EMBARGO = Duration.ofMinutes(16);
  static final LocalDate ALL_START = LocalDate.of(2016, 1, 1);

  private record Key(String symbol, Timeframe tf, Instant from, Instant to, AdjustmentMode adj, boolean ext, int limit) {}

  private record Cached(BarSeries series, Duration ttl) {}

  private final MarketDataProvider provider;
  private final boolean live;
  private final MarketFeed feed;
  private final MarketCalendar calendar;
  private final TimeSource time;
  private final Cache<Key, Cached> cache = Caffeine.newBuilder()
      .maximumSize(2_000)
      .expireAfter(new Expiry<Key, Cached>() {
        @Override
        public long expireAfterCreate(Key k, Cached v, long now) {
          return v.ttl().toNanos();
        }

        @Override
        public long expireAfterUpdate(Key k, Cached v, long now, long current) {
          return v.ttl().toNanos();
        }

        @Override
        public long expireAfterRead(Key k, Cached v, long now, long current) {
          return current;
        }
      })
      .build();

  public BarService(MarketDataProvider provider, boolean live, MarketFeed feed, MarketCalendar calendar,
                    TimeSource time) {
    this.provider = provider;
    this.live = live;
    this.feed = feed;
    this.calendar = calendar;
    this.time = time;
  }

  public static Timeframe defaultTimeframe(ChartRange range) {
    if (range == null) {
      return Timeframe.DAY1;
    }
    return switch (range) {
      case D1 -> Timeframe.MIN1;
      case D5 -> Timeframe.MIN5;
      case M1 -> Timeframe.MIN30;
      case M3, M6, YTD, Y1 -> Timeframe.DAY1;
      case Y5 -> Timeframe.WEEK1;
      case ALL -> Timeframe.MONTH1;
    };
  }

  /** The start of a chart range at {@code now}. */
  public Instant rangeStart(ChartRange range, Instant now, boolean extendedHours) {
    LocalDate day = calendar.currentPnlDay(now);
    LocalDate today = Et.date(now);
    return switch (range) {
      case D1 -> sessionStart(day, extendedHours);
      case D5 -> {
        LocalDate d = day;
        for (int i = 0; i < 4; i++) {
          d = calendar.previousTradingDay(d);
        }
        yield sessionStart(d, extendedHours);
      }
      case M1 -> Et.startOfDay(today.minusMonths(1));
      case M3 -> Et.startOfDay(today.minusMonths(3));
      case M6 -> Et.startOfDay(today.minusMonths(6));
      case YTD -> Et.startOfDay(today.withDayOfYear(1));
      case Y1 -> Et.startOfDay(today.minusYears(1));
      case Y5 -> Et.startOfDay(today.minusYears(5));
      case ALL -> Et.startOfDay(ALL_START);
    };
  }

  private Instant sessionStart(LocalDate d, boolean extendedHours) {
    TradingDay td = calendar.day(d).orElseThrow();
    return extendedHours ? td.preOpenAt() : td.rthOpenAt();
  }

  public BarSeries bars(BarRequest r) {
    Instant now = time.now();
    Timeframe tf = r.timeframeOrNull() != null ? r.timeframeOrNull() : defaultTimeframe(r.rangeOrNull());
    AdjustmentMode adj = r.adjustment() != null ? r.adjustment() : AdjustmentMode.SPLIT;
    int limit = r.limitOrNull() == null ? MAX_LIMIT : r.limitOrNull();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw ApiException.validation("limit", "limit must be 1.." + MAX_LIMIT);
    }
    Instant end = r.endOrNull() != null && r.endOrNull().isBefore(now) ? r.endOrNull() : now;
    Instant start;
    if (r.rangeOrNull() != null) {
      start = rangeStart(r.rangeOrNull(), now, r.extendedHours());
    } else if (r.startOrNull() != null) {
      start = r.startOrNull();
    } else {
      start = defaultStart(tf, end, limit);
    }
    if (!start.isBefore(end)) {
      throw ApiException.validation("start", "start must be before end");
    }
    Key key = new Key(r.symbol(), tf, start.truncatedTo(ChronoUnit.MINUTES),
        end.truncatedTo(BarMath.intraday(tf) ? ChronoUnit.MINUTES : ChronoUnit.HOURS), adj, r.extendedHours(), limit);
    boolean past = end.isBefore(now.minus(Duration.ofDays(1)));
    Cached c = cache.getIfPresent(key);
    if (c != null) {
      return c.series();
    }
    BarSeries series = live ? liveBars(r.symbol(), tf, start, end, adj, r.extendedHours(), limit, now)
        : simBars(r.symbol(), tf, start, end, adj, r.extendedHours(), limit);
    cache.put(key, new Cached(series, past ? Duration.ofHours(24) : ttl(tf)));
    return series;
  }

  static Duration ttl(Timeframe tf) {
    return switch (tf) {
      case MIN1 -> Duration.ofSeconds(30);
      case MIN5, MIN15, MIN30, HOUR1 -> Duration.ofMinutes(5);
      default -> Duration.ofHours(1);
    };
  }

  private static Instant defaultStart(Timeframe tf, Instant end, int limit) {
    Duration len = BarMath.length(tf);
    if (len != null) {
      return end.minus(len.multipliedBy(Math.min(limit, MAX_LIMIT) * 3L));   // overnight gaps: take extra calendar time
    }
    long days = switch (tf) {
      case WEEK1 -> 7L * limit;
      case MONTH1 -> 31L * limit;
      default -> Math.round(limit * 1.5);
    };
    return end.minus(Duration.ofDays(Math.min(days, 365L * 12)));
  }

  private BarSeries simBars(String symbol, Timeframe tf, Instant start, Instant end, AdjustmentMode adj, boolean ext,
                            int limit) {
    Map<String, List<Bar>> bars = provider.bars(new BarQuery(Set.of(symbol), tf, start, end, adj, ext, limit, null));
    return new BarSeries(symbol, tf, adj, tail(bars.getOrDefault(symbol, List.of()), limit), null, provider.id());
  }

  private BarSeries liveBars(String symbol, Timeframe tf, Instant start, Instant end, AdjustmentMode adj, boolean ext,
                             int limit, Instant now) {
    Instant sipEnd = now.minus(SIP_EMBARGO);
    List<Bar> history = List.of();
    if (start.isBefore(sipEnd)) {
      Instant to = end.isBefore(sipEnd) ? end : sipEnd;
      history = fetch(new BarQuery(Set.of(symbol), tf, start, to, adj, ext, limit, "sip"), symbol);
    }
    List<Bar> tail = List.of();
    if (end.isAfter(sipEnd) && (BarMath.intraday(tf) || tf == Timeframe.DAY1)) {
      Instant from = start.isAfter(sipEnd) ? start : sipEnd;
      Duration len = BarMath.length(tf);
      if (len != null) {
        from = Instant.ofEpochSecond(Math.floorDiv(from.getEpochSecond(), len.getSeconds()) * len.getSeconds());
      } else {
        from = Et.startOfDay(Et.date(from));
      }
      tail = fetch(new BarQuery(Set.of(symbol), tf, from, end, adj, ext, limit, "iex"), symbol);
      if (tf == Timeframe.MIN1) {
        Optional<Bar> forming = feed.currentMinuteBar(symbol);
        if (forming.isPresent() && !forming.get().start().isBefore(from)) {
          tail = BarMath.mergeByStart(List.of(forming.get()), tail);
        }
      }
    }
    List<Bar> merged = BarMath.mergeByStart(history, tail);
    if (!ext && BarMath.intraday(tf)) {
      merged = merged.stream().filter(this::inRegularSession).toList();
    }
    Instant tailFrom = null;
    if (!tail.isEmpty()) {
      Instant lastSip = history.isEmpty() ? null : history.get(history.size() - 1).start();
      for (Bar b : merged) {
        if (b.partialVolume() && (lastSip == null || b.start().isAfter(lastSip))) {
          tailFrom = b.start();
          break;
        }
      }
    }
    return new BarSeries(symbol, tf, adj, tail(merged, limit), tailFrom, "alpaca");
  }

  private boolean inRegularSession(Bar b) {
    Optional<TradingDay> d = calendar.day(Et.date(b.start()));
    return d.isPresent() && !b.start().isBefore(d.get().rthOpenAt()) && b.start().isBefore(d.get().rthCloseAt());
  }

  private List<Bar> fetch(BarQuery q, String symbol) {
    try {
      return provider.bars(q, RefreshPriority.UI).getOrDefault(symbol, List.of());
    } catch (ProviderException e) {
      log.warn("bars {} {} ({}) failed: {}", symbol, q.tf(), q.feed(), e.getMessage());
      return List.of();
    }
  }

  private static List<Bar> tail(List<Bar> bars, int limit) {
    if (bars.size() <= limit) {
      return bars;
    }
    return new ArrayList<>(bars.subList(bars.size() - limit, bars.size()));
  }
}
