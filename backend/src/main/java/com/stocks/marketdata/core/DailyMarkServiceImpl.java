package com.stocks.marketdata.core;

import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.DailyMarkService;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.PointInTimeMarket;
import com.stocks.marketdata.spi.ProviderException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daily marks (§15.5, §16.4), the {@link DailyMarkService} WS4's {@code EodSnapshotJob} calls:
 * <ul>
 *   <li>{@link #ensureOfficialCloses}: {@code official_close} = the SIP {@code 1Day} close (raw), after the regular
 *       close; the live quote book learns it too.</li>
 *   <li>{@link #ensureDayEndMarks}: {@code day_end_mark} = the close of the last SIP {@code 1Min} bar starting before
 *       {@code post_close_at} (basis {@code SIP_1MIN}, {@code FINAL}; needs the 16-minute SIP embargo to have
 *       passed), else the official close ({@code OFFICIAL_CLOSE}), else the day's last trade ({@code LAST_TRADE}),
 *       both {@code PROVISIONAL} and re-marked the next morning. The SIM writes {@code SIMULATED}, {@code FINAL}.</li>
 * </ul>
 * Both are idempotent: symbols that already have a value ({@code FINAL} for day-end marks) are skipped.
 */
public class DailyMarkServiceImpl implements DailyMarkService {

  private static final Logger log = LoggerFactory.getLogger(DailyMarkServiceImpl.class);
  static final int BATCH = 100;
  static final Duration SIP_EMBARGO = Duration.ofMinutes(16);
  static final Duration DAY_END_WINDOW = Duration.ofMinutes(60);

  private final MarketDataProvider provider;
  private final PointInTimeMarket sim;
  private final MarketFeed feed;
  private final DailyMarkRepository repository;
  private final MarketCalendar calendar;
  private final TimeSource time;

  /** {@code simOrNull} non-null: the SIM computes the marks; otherwise {@code provider} serves SIP bars. */
  public DailyMarkServiceImpl(MarketDataProvider provider, PointInTimeMarket simOrNull, MarketFeed feed,
                              DailyMarkRepository repository, MarketCalendar calendar, TimeSource time) {
    this.provider = provider;
    this.sim = simOrNull;
    this.feed = feed;
    this.repository = repository;
    this.calendar = calendar;
    this.time = time;
  }

  @Override
  public void ensureOfficialCloses(LocalDate tradeDate, Set<String> symbols) {
    Optional<TradingDay> day = calendar.day(tradeDate);
    Instant now = time.now();
    if (day.isEmpty() || now.isBefore(day.get().rthCloseAt()) || symbols.isEmpty()) {
      return;
    }
    Set<String> missing = new TreeSet<>(symbols);
    missing.removeAll(repository.officialCloses(tradeDate, symbols).keySet());
    if (missing.isEmpty()) {
      return;
    }
    int written = 0;
    if (sim != null) {
      for (String s : missing) {
        Optional<BigDecimal> c = sim.officialClose(s, tradeDate);
        if (c.isPresent()) {
          repository.upsertOfficialClose(s, tradeDate, c.get(), now);
          written++;
        }
      }
    } else {
      Instant from = Et.startOfDay(tradeDate);
      Instant to = Et.startOfDay(tradeDate.plusDays(1)).minusSeconds(1);
      for (List<String> batch : batches(missing)) {
        Map<String, List<Bar>> bars = fetch(new BarQuery(Set.copyOf(batch), Timeframe.DAY1, from, to, AdjustmentMode.RAW,
            false, 10_000, "sip"));
        for (Map.Entry<String, List<Bar>> e : bars.entrySet()) {
          for (Bar b : e.getValue()) {
            if (Et.date(b.start()).equals(tradeDate) && b.close() != null) {
              repository.upsertOfficialClose(e.getKey(), tradeDate, b.close(), time.now());
              feed.officialClose(e.getKey(), tradeDate, b.close());
              written++;
            }
          }
        }
      }
    }
    log.info("Daily marks {}: {} official closes written ({} requested)", tradeDate, written, missing.size());
  }

  @Override
  public void ensureDayEndMarks(LocalDate tradeDate, Set<String> symbols) {
    Optional<TradingDay> day = calendar.day(tradeDate);
    Instant now = time.now();
    if (day.isEmpty() || now.isBefore(day.get().postCloseAt()) || symbols.isEmpty()) {
      return;
    }
    Set<String> todo = new TreeSet<>(symbols);
    todo.removeAll(repository.withDayEnd(tradeDate, DailyMarkRepository.FINAL));
    if (todo.isEmpty()) {
      return;
    }
    Instant postClose = day.get().postCloseAt();
    if (sim != null) {
      for (String s : todo) {
        simDayEnd(sim, calendar, s, tradeDate, now).ifPresent(p ->
            repository.upsertDayEnd(s, tradeDate, p, "SIMULATED", DailyMarkRepository.FINAL, time.now()));
      }
      return;
    }
    Set<String> left = new TreeSet<>(todo);
    if (!now.isBefore(postClose.plus(SIP_EMBARGO))) {
      for (List<String> batch : batches(todo)) {
        Map<String, List<Bar>> bars = fetch(new BarQuery(Set.copyOf(batch), Timeframe.MIN1,
            postClose.minus(DAY_END_WINDOW), postClose.minusSeconds(1), AdjustmentMode.RAW, true, 10_000, "sip"));
        for (Map.Entry<String, List<Bar>> e : bars.entrySet()) {
          Bar last = null;
          for (Bar b : e.getValue()) {
            if (b.start().isBefore(postClose) && (last == null || b.start().isAfter(last.start()))) {
              last = b;
            }
          }
          if (last != null && last.close() != null) {
            repository.upsertDayEnd(e.getKey(), tradeDate, last.close(), "SIP_1MIN", DailyMarkRepository.FINAL,
                time.now());
            left.remove(e.getKey());
          }
        }
      }
    }
    Map<String, BigDecimal> closes = repository.officialCloses(tradeDate, left);
    for (String s : List.copyOf(left)) {
      BigDecimal oc = closes.get(s);
      if (oc != null) {
        repository.upsertDayEnd(s, tradeDate, oc, "OFFICIAL_CLOSE", DailyMarkRepository.PROVISIONAL, time.now());
        left.remove(s);
        continue;
      }
      Trade t = feed.markInputs(s).lastTrade();
      if (t != null && Et.date(t.ts()).equals(tradeDate) && !t.ts().isAfter(postClose)) {
        repository.upsertDayEnd(s, tradeDate, t.price(), "LAST_TRADE", DailyMarkRepository.PROVISIONAL, time.now());
        left.remove(s);
      }
    }
    log.info("Daily marks {}: day-end marks for {} symbols ({} without any price)", tradeDate, todo.size() - left.size(),
        left.size());
  }

  /** The SIM day-end mark: the last print before {@code post_close_at}, once the post session is over. */
  static Optional<BigDecimal> simDayEnd(PointInTimeMarket sim, MarketCalendar calendar, String symbol,
                                        LocalDate tradeDate, Instant now) {
    Optional<TradingDay> day = calendar.day(tradeDate);
    if (day.isEmpty() || now.isBefore(day.get().postCloseAt())) {
      return Optional.empty();
    }
    Instant at = day.get().postCloseAt().minus(1, ChronoUnit.MICROS);
    return sim.lastTradeAt(symbol, at).map(Trade::price);
  }

  private Map<String, List<Bar>> fetch(BarQuery q) {
    try {
      return provider.bars(q, RefreshPriority.BACKGROUND);
    } catch (ProviderException | UnsupportedOperationException e) {
      log.warn("Daily marks: {} bars for {} symbols failed: {}", q.tf(), q.symbols().size(), e.getMessage());
      return Map.of();
    }
  }

  private static List<List<String>> batches(Set<String> symbols) {
    List<String> all = new ArrayList<>(symbols);
    List<List<String>> out = new ArrayList<>();
    for (int i = 0; i < all.size(); i += BATCH) {
      out.add(all.subList(i, Math.min(all.size(), i + BATCH)));
    }
    return out;
  }
}
