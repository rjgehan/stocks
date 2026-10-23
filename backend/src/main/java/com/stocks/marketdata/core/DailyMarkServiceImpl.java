package com.stocks.marketdata.core;

import com.stocks.common.domain.Crypto;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.DailyMarkService;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.PointInTimeMarket;
import com.stocks.marketdata.spi.ProviderException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
 *
 * <p>Crypto pairs (§29) have no official close. Their day-end mark is the close of the last 1-minute bar before the
 * league's cut-off of day D ({@link #cutoff}: {@code post_close_at} on a trading day, 20:00 ET on any other day),
 * basis {@code CRYPTO_1MIN}, {@code FINAL} (crypto bars have no embargo), else the last trade before the cut-off
 * ({@code LAST_TRADE}, {@code PROVISIONAL}); every calendar day has one, weekends included.
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
    missing.removeIf(s -> Crypto.isCrypto(s) || OptionSymbol.isOption(s));   // no official close (§29, §30)
    if (missing.isEmpty()) {
      return;
    }
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
  public Optional<BigDecimal> officialClose(String symbol, LocalDate tradeDate) {
    return Optional.ofNullable(repository.officialCloses(tradeDate, Set.of(symbol)).get(symbol));
  }

  @Override
  public void ensureDayEndMarks(LocalDate tradeDate, Set<String> symbols) {
    Set<String> crypto = new TreeSet<>();
    Set<String> options = new TreeSet<>();
    Set<String> stocks = new TreeSet<>();
    symbols.forEach(s -> (Crypto.isCrypto(s) ? crypto : OptionSymbol.isOption(s) ? options : stocks).add(s));
    ensureCryptoDayEndMarks(tradeDate, crypto);
    ensureOptionDayEndMarks(tradeDate, options);
    Optional<TradingDay> day = calendar.day(tradeDate);
    Instant now = time.now();
    if (day.isEmpty() || now.isBefore(day.get().postCloseAt()) || stocks.isEmpty()) {
      return;
    }
    Set<String> todo = new TreeSet<>(stocks);
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

  /**
   * §30: option day-end marks once the regular session of {@code tradeDate} has closed: the mid (the bid without an
   * ask) of the last quote, basis {@code OPTION_MID}; {@code FINAL} when that quote was observed after the close (the
   * closing quote), otherwise {@code PROVISIONAL} (re-marked like a stock's). The SIM prices the closing quote.
   */
  private void ensureOptionDayEndMarks(LocalDate tradeDate, Set<String> symbols) {
    Optional<TradingDay> day = calendar.day(tradeDate);
    Instant now = time.now();
    if (symbols.isEmpty() || day.isEmpty() || now.isBefore(day.get().rthCloseAt())) {
      return;
    }
    Set<String> todo = new TreeSet<>(symbols);
    todo.removeAll(repository.withDayEnd(tradeDate, DailyMarkRepository.FINAL));
    Instant close = day.get().rthCloseAt();
    int written = 0;
    for (String s : todo) {
      Quote q;
      boolean closing;
      if (sim != null) {
        q = sim.quoteAt(s, close.minus(1, ChronoUnit.MICROS)).orElse(null);
        closing = true;
      } else {
        MarketFeed.MarkInputs in = feed.markInputs(s);
        q = in.quote();
        closing = in.quoteConfirmedAt() != null && !in.quoteConfirmedAt().isBefore(close)
            && q != null && q.ts() != null && Et.date(q.ts()).equals(tradeDate);
      }
      BigDecimal mid = MarkService.optionMid(q);
      if (mid != null && mid.signum() > 0) {
        repository.upsertDayEnd(s, tradeDate, mid, sim != null ? "SIMULATED" : "OPTION_MID",
            closing ? DailyMarkRepository.FINAL : DailyMarkRepository.PROVISIONAL, time.now());
        written++;
      }
    }
    log.info("Daily marks {}: option day-end marks for {} of {} contracts", tradeDate, written, todo.size());
  }

  /** §29: crypto day-end marks at the league cut-off of {@code tradeDate}, any calendar day. */
  private void ensureCryptoDayEndMarks(LocalDate tradeDate, Set<String> symbols) {
    Instant cutoff = cutoff(calendar, tradeDate);
    Instant now = time.now();
    if (symbols.isEmpty() || now.isBefore(cutoff)) {
      return;
    }
    Set<String> todo = new TreeSet<>(symbols);
    todo.removeAll(repository.withDayEnd(tradeDate, DailyMarkRepository.FINAL));
    if (todo.isEmpty()) {
      return;
    }
    if (sim != null) {
      for (String s : todo) {
        simDayEnd(sim, calendar, s, tradeDate, now).ifPresent(p ->
            repository.upsertDayEnd(s, tradeDate, p, "SIMULATED", DailyMarkRepository.FINAL, time.now()));
      }
      return;
    }
    Set<String> left = new TreeSet<>(todo);
    Map<String, List<Bar>> bars = fetch(new BarQuery(Set.copyOf(todo), Timeframe.MIN1, cutoff.minus(DAY_END_WINDOW),
        cutoff.minusSeconds(1), AdjustmentMode.RAW, true, 10_000, null));
    for (Map.Entry<String, List<Bar>> e : bars.entrySet()) {
      Bar last = null;
      for (Bar b : e.getValue()) {
        if (b.start().isBefore(cutoff) && (last == null || b.start().isAfter(last.start()))) {
          last = b;
        }
      }
      if (last != null && last.close() != null) {
        repository.upsertDayEnd(e.getKey(), tradeDate, last.close(), "CRYPTO_1MIN", DailyMarkRepository.FINAL,
            time.now());
        left.remove(e.getKey());
      }
    }
    for (String s : List.copyOf(left)) {
      Trade t = feed.markInputs(s).lastTrade();
      if (t != null && !t.ts().isAfter(cutoff) && t.ts().isAfter(cutoff.minus(Duration.ofDays(1)))) {
        repository.upsertDayEnd(s, tradeDate, t.price(), "LAST_TRADE", DailyMarkRepository.PROVISIONAL, time.now());
        left.remove(s);
      }
    }
    log.info("Daily marks {}: crypto day-end marks for {} pairs ({} without any price)", tradeDate,
        todo.size() - left.size(), left.size());
  }

  /**
   * The league's P&amp;L cut-off of {@code date} (§16.3, §29): {@code post_close_at} on a trading day, else 20:00 ET
   * (the same instant {@code PnlCalendar.cutoff} uses).
   */
  public static Instant cutoff(MarketCalendar calendar, LocalDate date) {
    return calendar.day(date).map(TradingDay::postCloseAt).orElseGet(() -> Et.at(date, LocalTime.of(20, 0)));
  }

  /**
   * The SIM day-end mark: the last print before {@code post_close_at}, once the post session is over; for a crypto
   * pair, the last print before the league cut-off of any calendar day (§29).
   */
  static Optional<BigDecimal> simDayEnd(PointInTimeMarket sim, MarketCalendar calendar, String symbol,
                                        LocalDate tradeDate, Instant now) {
    Instant end;
    if (Crypto.isCrypto(symbol)) {
      end = cutoff(calendar, tradeDate);
    } else {
      Optional<TradingDay> day = calendar.day(tradeDate);
      if (day.isEmpty()) {
        return Optional.empty();
      }
      end = day.get().postCloseAt();
    }
    if (now.isBefore(end)) {
      return Optional.empty();
    }
    return sim.lastTradeAt(symbol, end.minus(1, ChronoUnit.MICROS)).map(Trade::price);
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
