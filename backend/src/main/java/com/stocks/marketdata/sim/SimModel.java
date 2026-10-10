package com.stocks.marketdata.sim;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.money.Prices;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.marketdata.core.Universe;
import com.stocks.marketdata.spi.PointInTimeMarket.DayStats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The SIM market model (§15.7): every price, quote, print and bar is a pure function of {@code (seed, symbol, time)}
 * given the calendar, so history and the "live" feed always agree and tests are exact.
 *
 * <ul>
 *   <li><b>Daily path.</b> Log closes follow a random walk over trading days (one return per trading date: drift,
 *       {@code beta} × a shared market factor, idiosyncratic noise), anchored so that the close on
 *       {@link #REF_DATE} equals the universe {@code ref_price}. The open gaps from the prior close; the after-hours
 *       price drifts from the close.</li>
 *   <li><b>Intraday.</b> Brownian bridges per session: PRE from the prior after-hours price to the open, REGULAR from
 *       the open to the close with U-shaped volatility (a time change), POST from the close to the after-hours price.
 *       Bridges are built by dyadic refinement ({@link SimMath#bridge}), so any instant is O(depth).</li>
 *   <li><b>Quotes.</b> Update instants come from a seeded Poisson process per second; at each update the quote is the
 *       mid ± a tier half-spread (×3 in PRE/POST), rounded to ticks; sizes are 100 × geometric.</li>
 *   <li><b>Prints.</b> Seeded Poisson arrivals: 45% at the bid, 45% at the ask, 10% inside; heavy-tailed sizes with
 *       some odd lots. All consolidated and eligible.</li>
 *   <li><b>Bars.</b> Intraday bars aggregate prints (so live and historical bars are identical); daily bars use the
 *       path's open and close and the bridge extremes at 256 points.</li>
 * </ul>
 * Every computation uses {@link StrictMath}; decimals are built from integers (never {@code BigDecimal(double)}).
 */
final class SimModel {

  static final LocalDate PATH_START = LocalDate.of(2016, 1, 4);
  static final LocalDate REF_DATE = LocalDate.of(2026, 10, 9);
  static final LocalDate PATH_END = LocalDate.of(2031, 12, 31);
  static final String SOURCE = "simulated";
  static final String VENUE = "SIM";

  private static final long MARKET_KEY = SimMath.hash("$MARKET");
  private static final double MARKET_SIGMA = 0.0105;
  private static final double ANNUAL_DRIFT = 0.08;
  /** U-shape strength of intraday volatility: density 1 + K (2u − 1)². */
  private static final double U_SHAPE = 2.0;
  private static final int SAMPLE_LEVEL = 8;
  private static final int SEG_PRE = 1;
  private static final int SEG_RTH = 2;
  private static final int SEG_POST = 3;

  /** Per-symbol parameters, all derived from {@code hash(seed, symbol)} (and the universe entry). */
  record Params(String symbol, long key, double logRef, double sigmaD, double beta, double sigmaIdio,
                LiquidityTier tier, double advShares, AssetType type) {

    double tierHalfSpreadBps() {
      return switch (tier) {
        case MEGA -> 1.0;
        case MID -> 5.0;
        case SMALL -> 25.0;
      };
    }

    double quoteRate(boolean regular) {
      return switch (tier) {
        case MEGA -> regular ? 3.0 : 0.3;
        case MID -> regular ? 1.0 : 0.1;
        case SMALL -> regular ? 0.3 : 0.05;
      };
    }

    double tradeRate(boolean regular) {
      return switch (tier) {
        case MEGA -> regular ? 1.0 : 0.08;
        case MID -> regular ? 0.3 : 0.03;
        case SMALL -> regular ? 0.08 : 0.01;
      };
    }
  }

  /** A session segment of a trading day. */
  record Segment(LocalDate date, int index, SessionType type, Instant start, Instant end) {}

  private record DayIndex(LocalDate[] days, Map<LocalDate, Integer> pos, int refIdx) {}

  private final long seed;
  private final MarketCalendar calendar;
  private final Function<String, Optional<Universe.Entry>> lookup;
  private final SimScenarios scenarios;
  private final Map<String, Optional<Params>> params = new ConcurrentHashMap<>();
  private final Map<String, double[]> closes = new ConcurrentHashMap<>();
  private volatile DayIndex index;

  SimModel(long seed, MarketCalendar calendar, Function<String, Optional<Universe.Entry>> lookup,
           SimScenarios scenarios) {
    this.seed = seed;
    this.calendar = calendar;
    this.lookup = lookup;
    this.scenarios = scenarios == null ? SimScenarios.none() : scenarios;
  }

  SimScenarios scenarios() {
    return scenarios;
  }

  MarketCalendar calendar() {
    return calendar;
  }

  // ------------------------------------------------------------------------------------------- parameters

  Optional<Params> params(String symbol) {
    return params.computeIfAbsent(symbol, s -> lookup.apply(s).map(e -> build(s, e)));
  }

  private Params build(String symbol, Universe.Entry e) {
    long key = SimMath.mix(seed, SimMath.hash(symbol));
    double refPrice = e.refPrice() != null && e.refPrice().signum() > 0 ? e.refPrice().doubleValue()
        : StrictMath.exp(StrictMath.log(20) + SimMath.uniform(SimMath.mix(key, 101)) * StrictMath.log(400.0 / 20));
    double u = SimMath.uniform(SimMath.mix(key, 102));
    double annual = 0.15 + (e.tier() == LiquidityTier.MEGA ? 0.30 : 0.50) * StrictMath.pow(u, 1.5);
    if (e.type() == AssetType.ETF) {
      annual *= 0.6;
    }
    double sigmaD = annual / StrictMath.sqrt(252);
    double beta = 0.6 + 0.9 * SimMath.uniform(SimMath.mix(key, 103));
    double idio2 = sigmaD * sigmaD - beta * beta * MARKET_SIGMA * MARKET_SIGMA;
    double sigmaIdio = StrictMath.sqrt(StrictMath.max(idio2, 0.0625 * sigmaD * sigmaD));
    double advUsd = switch (e.tier()) {
      case MEGA -> 1.5e9;
      case MID -> 1.5e8;
      case SMALL -> 1.0e7;
    } * StrictMath.exp(0.5 * SimMath.normal(SimMath.mix(key, 104)));
    return new Params(symbol, key, StrictMath.log(refPrice), sigmaD, beta, sigmaIdio, e.tier(), advUsd / refPrice,
        e.type());
  }

  // ------------------------------------------------------------------------------------------- daily path

  private DayIndex index() {
    DayIndex i = index;
    if (i == null) {
      synchronized (this) {
        i = index;
        if (i == null) {
          List<LocalDate> days = new ArrayList<>(6000);
          for (LocalDate d = PATH_START; !d.isAfter(PATH_END); d = d.plusDays(1)) {
            if (calendar.isTradingDay(d)) {
              days.add(d);
            }
          }
          Map<LocalDate, Integer> pos = new HashMap<>(days.size() * 2);
          int ref = 0;
          for (int k = 0; k < days.size(); k++) {
            pos.put(days.get(k), k);
            if (!days.get(k).isAfter(REF_DATE)) {
              ref = k;
            }
          }
          i = new DayIndex(days.toArray(LocalDate[]::new), Map.copyOf(pos), ref);
          index = i;
        }
      }
    }
    return i;
  }

  private double drift(Params p) {
    return ANNUAL_DRIFT / 252 - p.sigmaD() * p.sigmaD() / 2;
  }

  /** The log return of a trading day (close over prior close), scenario gaps included. */
  private double dailyReturn(Params p, LocalDate d) {
    long ed = d.toEpochDay();
    double m = MARKET_SIGMA * SimMath.normal(SimMath.mix(seed, MARKET_KEY, ed));
    double eps = SimMath.normal(SimMath.mix(p.key(), ed, 1));
    return drift(p) + p.beta() * m + p.sigmaIdio() * eps + scenarios.gapLog(p.symbol(), d);
  }

  private double[] logCloses(Params p) {
    return closes.computeIfAbsent(p.symbol(), s -> {
      DayIndex idx = index();
      int n = idx.days().length;
      double[] c = new double[n];
      if (n == 0) {
        return c;
      }
      c[idx.refIdx()] = p.logRef();
      for (int k = idx.refIdx() + 1; k < n; k++) {
        c[k] = c[k - 1] + dailyReturn(p, idx.days()[k]);
      }
      for (int k = idx.refIdx() - 1; k >= 0; k--) {
        c[k] = c[k + 1] - dailyReturn(p, idx.days()[k + 1]);
      }
      return c;
    });
  }

  OptionalDouble logClose(Params p, int k) {
    double[] c = logCloses(p);
    return k >= 0 && k < c.length ? OptionalDouble.of(c[k]) : OptionalDouble.empty();
  }

  private double prevLogClose(Params p, int k) {
    double[] c = logCloses(p);
    return k > 0 ? c[k - 1] : c[0] - dailyReturn(p, index().days()[0]);
  }

  double logOpen(Params p, int k) {
    LocalDate d = index().days()[k];
    double gapScenario = scenarios.gapLog(p.symbol(), d);
    double r = dailyReturn(p, d) - gapScenario;
    double gap = 0.3 * (r - drift(p)) + 0.2 * p.sigmaD() * SimMath.normal(SimMath.mix(p.key(), d.toEpochDay(), 2));
    return prevLogClose(p, k) + gap + gapScenario;
  }

  double logPost(Params p, int k) {
    LocalDate d = index().days()[k];
    return logCloses(p)[k] + 0.1 * p.sigmaD() * SimMath.normal(SimMath.mix(p.key(), d.toEpochDay(), 3));
  }

  private double logPrevPost(Params p, int k) {
    return k > 0 ? logPost(p, k - 1) : prevLogClose(p, k);
  }

  Optional<Integer> dayIndex(LocalDate d) {
    return Optional.ofNullable(index().pos().get(d));
  }

  // ------------------------------------------------------------------------------------------- intraday path

  /** U-shaped business time: the cumulative intraday variance fraction at clock fraction {@code u}. */
  static double businessTime(double u) {
    double x = 2 * u - 1;
    return (u + U_SHAPE * (x * x * x + 1) / 6) / (1 + U_SHAPE / 3);
  }

  private static int depthFor(Instant start, Instant end) {
    long seconds = StrictMath.max(2, Duration.between(start, end).getSeconds());
    return StrictMath.max(8, 64 - Long.numberOfLeadingZeros(seconds - 1));
  }

  /** The session segment containing {@code t}, or empty when the market is closed at {@code t}. */
  Optional<Segment> segmentAt(Instant t) {
    LocalDate date = Et.date(t);
    Optional<TradingDay> day = calendar.day(date);
    Optional<Integer> k = dayIndex(date);
    if (day.isEmpty() || k.isEmpty()) {
      return Optional.empty();
    }
    TradingDay d = day.get();
    if (t.isBefore(d.preOpenAt()) || !t.isBefore(d.postCloseAt())) {
      return Optional.empty();
    }
    if (t.isBefore(d.rthOpenAt())) {
      return Optional.of(new Segment(date, k.get(), SessionType.PRE, d.preOpenAt(), d.rthOpenAt()));
    }
    if (t.isBefore(d.rthCloseAt())) {
      return Optional.of(new Segment(date, k.get(), SessionType.REGULAR, d.rthOpenAt(), d.rthCloseAt()));
    }
    return Optional.of(new Segment(date, k.get(), SessionType.POST, d.rthCloseAt(), d.postCloseAt()));
  }

  /** The last non-empty segment ending at or before {@code t} (the market was last open there). */
  Optional<Segment> lastSegmentBefore(Instant t) {
    LocalDate date = Et.date(t);
    for (int i = 0; i < 15; i++, date = date.minusDays(1)) {
      Optional<TradingDay> day = calendar.day(date);
      Optional<Integer> k = dayIndex(date);
      if (day.isEmpty() || k.isEmpty()) {
        continue;
      }
      TradingDay d = day.get();
      if (!d.postCloseAt().isAfter(t)) {
        return d.postCloseAt().isAfter(d.rthCloseAt())
            ? Optional.of(new Segment(date, k.get(), SessionType.POST, d.rthCloseAt(), d.postCloseAt()))
            : Optional.of(new Segment(date, k.get(), SessionType.REGULAR, d.rthOpenAt(), d.rthCloseAt()));
      }
    }
    return Optional.empty();
  }

  /** Log mid (adjusted, before the raw split factor) at {@code t}, frozen at the after-hours price when closed. */
  OptionalDouble logMid(Params p, Instant t) {
    Optional<Segment> seg = segmentAt(t);
    if (seg.isEmpty()) {
      Optional<Segment> last = lastSegmentBefore(t);
      if (last.isEmpty()) {
        return OptionalDouble.empty();
      }
      return OptionalDouble.of(segmentEnd(p, last.get()));
    }
    return OptionalDouble.of(logMidIn(p, seg.get(), t) + scenarios.logDip(p.symbol(), t));
  }

  private double segmentEnd(Params p, Segment s) {
    return s.type() == SessionType.POST ? logPost(p, s.index()) : logCloses(p)[s.index()];
  }

  private double logMidIn(Params p, Segment s, Instant t) {
    int k = s.index();
    double x0;
    double x1;
    double sigma;
    int segId;
    boolean uShape = false;
    switch (s.type()) {
      case PRE -> {
        x0 = logPrevPost(p, k);
        x1 = logOpen(p, k);
        sigma = 0.12 * p.sigmaD();
        segId = SEG_PRE;
      }
      case REGULAR -> {
        x0 = logOpen(p, k);
        x1 = logCloses(p)[k];
        sigma = 0.8 * p.sigmaD();
        segId = SEG_RTH;
        uShape = true;
      }
      default -> {
        x0 = logCloses(p)[k];
        x1 = logPost(p, k);
        sigma = 0.12 * p.sigmaD();
        segId = SEG_POST;
      }
    }
    double len = Duration.between(s.start(), s.end()).toNanos();
    double u = Duration.between(s.start(), t).toNanos() / len;
    double tau = uShape ? businessTime(u) : u;
    long key = SimMath.mix(p.key(), s.date().toEpochDay(), segId);
    return x0 + (x1 - x0) * tau + sigma * SimMath.bridge(key, depthFor(s.start(), s.end()), tau);
  }

  /** Raw-price factor (a split scenario scales prices dated before its ex-date). */
  double rawFactor(Params p, LocalDate date) {
    if (!scenarios.hasSplits(p.symbol())) {
      return 1;
    }
    return scenarios.rawFactorDenominator(p.symbol(), date).doubleValue()
        / scenarios.rawFactorNumerator(p.symbol(), date).doubleValue();
  }

  // ------------------------------------------------------------------------------------------- prices

  /** Nearest tick ($0.01 at or above $1, $0.0001 below), scale 4. */
  static BigDecimal toTick(double price) {
    BigDecimal p = SimMath.decimal6(StrictMath.max(price, 0.0001));
    BigDecimal tick = Prices.tick(p);
    return p.divide(tick, 0, RoundingMode.HALF_EVEN).multiply(tick).setScale(Prices.SCALE, RoundingMode.UNNECESSARY);
  }

  Optional<BigDecimal> priceAt(Params p, Instant t) {
    OptionalDouble lm = logMid(p, t);
    if (lm.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(toTick(StrictMath.exp(lm.getAsDouble()) * rawFactor(p, Et.date(t))));
  }

  Optional<BigDecimal> officialClose(Params p, LocalDate date) {
    return dayIndex(date).map(k -> toTick(StrictMath.exp(logCloses(p)[k]) * rawFactor(p, date)));
  }

  /** The quote at an update instant {@code u} in segment {@code s}. */
  Quote quoteAt(Params p, Segment s, Instant u) {
    double mid = StrictMath.exp(logMidIn(p, s, u) + scenarios.logDip(p.symbol(), u)) * rawFactor(p, s.date());
    double halfBps = p.tierHalfSpreadBps() * (s.type() == SessionType.REGULAR ? 1 : 3);
    BigDecimal midD = SimMath.decimal6(StrictMath.max(mid, 0.0002));
    BigDecimal tick = Prices.tick(midD);
    BigDecimal half = midD.multiply(SimMath.decimal6(halfBps / 10_000)).setScale(6, RoundingMode.HALF_EVEN);
    BigDecimal minHalf = tick.divide(BigDecimal.TWO, 6, RoundingMode.HALF_EVEN);
    if (half.compareTo(minHalf) < 0) {
      half = minHalf;
    }
    BigDecimal bid = Rounding.TICK_DOWN.apply(midD.subtract(half));
    BigDecimal ask = Rounding.TICK_UP.apply(midD.add(half));
    if (bid.signum() <= 0) {
      bid = Prices.SUBDOLLAR_TICK.setScale(Prices.SCALE);
    }
    if (ask.compareTo(bid) <= 0) {
      ask = bid.add(Prices.tick(bid)).setScale(Prices.SCALE, RoundingMode.UNNECESSARY);
    }
    long uk = SimMath.mix(p.key(), u.getEpochSecond(), u.getNano(), 9);
    long bidSize = 100L * (1 + geometric(SimMath.mix(uk, 1)));
    long askSize = 100L * (1 + geometric(SimMath.mix(uk, 2)));
    if (scenarios.oneSided(p.symbol(), u)) {
      bid = BigDecimal.ZERO.setScale(Prices.SCALE);
      bidSize = 0;
    }
    return new Quote(p.symbol(), bid, bidSize, ask, askSize, VENUE, VENUE, u, u, DataQuality.SIMULATED, SOURCE);
  }

  private static long geometric(long key) {
    double u = SimMath.uniformOpen(key);
    return StrictMath.min(30, (long) StrictMath.floor(StrictMath.log(u) / StrictMath.log(0.65)));
  }

  /** The update or print instants of second {@code b} (sorted), from a Poisson process of rate {@code lambda}. */
  private static Instant[] arrivals(long key, long b, double lambda) {
    long k = SimMath.mix(key, b);
    int n = SimMath.poisson(lambda, k);
    Instant[] out = new Instant[n];
    for (int j = 0; j < n; j++) {
      long micros = (long) StrictMath.floor(SimMath.uniform(SimMath.mix(k, j)) * 1_000_000d);
      out[j] = Instant.ofEpochSecond(b, micros * 1_000);
    }
    java.util.Arrays.sort(out);
    return out;
  }

  private long quoteKey(Params p) {
    return SimMath.mix(p.key(), 7);
  }

  private long tradeKey(Params p) {
    return SimMath.mix(p.key(), 8);
  }

  /**
   * The quote in force at {@code t}: the last update at or before it within the current session (or the last
   * session, when closed), not after a freeze (halt or blackout) started.
   */
  Optional<Quote> quoteAt(Params p, Instant t) {
    Optional<Segment> seg = segmentAt(t);
    Instant at = t;
    if (seg.isEmpty()) {
      seg = lastSegmentBefore(t);
      if (seg.isEmpty()) {
        return Optional.empty();
      }
      at = seg.get().end().minus(1, ChronoUnit.MICROS);
    }
    Optional<Instant> frozen = scenarios.frozenSince(p.symbol(), at);
    if (frozen.isPresent() && frozen.get().isAfter(seg.get().start())) {
      at = frozen.get().minus(1, ChronoUnit.MICROS);
    }
    Segment s = seg.get();
    Instant update = latestArrival(quoteKey(p), p.quoteRate(s.type() == SessionType.REGULAR), s.start(), at);
    return Optional.of(quoteAt(p, s, update));
  }

  /** The latest arrival in {@code [start, at]}; {@code start} itself (an implicit update at the session open). */
  private static Instant latestArrival(long key, double lambda, Instant start, Instant at) {
    long first = start.getEpochSecond();
    for (long b = at.getEpochSecond(); b >= first && b > at.getEpochSecond() - 3600; b--) {
      Instant[] arr = arrivals(key, b, lambda);
      for (int j = arr.length - 1; j >= 0; j--) {
        if (!arr[j].isAfter(at) && !arr[j].isBefore(start)) {
          return arr[j];
        }
      }
    }
    return start;
  }

  private Trade tradeAt(Params p, Segment s, Instant ts, long b, int j) {
    Quote q = quoteAt(p, s, ts);
    long k = SimMath.mix(tradeKey(p), b, j, 11);
    double v = SimMath.uniform(k);
    BigDecimal price;
    if (v < 0.45 && q.bid().signum() > 0) {
      price = q.bid();
    } else if (v < 0.9) {
      price = q.ask();
    } else {
      BigDecimal mid = q.bid().signum() > 0 ? q.bid().add(q.ask()).divide(BigDecimal.TWO, 6, RoundingMode.HALF_EVEN)
          : q.ask();
      price = Rounding.TICK_DOWN.apply(mid);
      if (price.compareTo(q.bid()) < 0) {
        price = q.bid();
      }
    }
    long size;
    double w = SimMath.uniform(SimMath.mix(k, 1));
    if (w < 0.2) {
      size = 1 + (long) StrictMath.floor(SimMath.uniform(SimMath.mix(k, 2)) * 99);
    } else {
      double pareto = StrictMath.pow(1 / SimMath.uniformOpen(SimMath.mix(k, 3)), 1 / 1.6) - 1;
      size = 100L * (1 + StrictMath.min(50, (long) StrictMath.floor(pareto)));
    }
    return new Trade(p.symbol(), price, size, ts, ts, VENUE, List.of(), true, true, "sim-" + b + "-" + j, SOURCE);
  }

  /** Prints in {@code (from, to]}, oldest first, at most {@code max}. */
  List<Trade> tradesBetween(Params p, Instant from, Instant to, int max) {
    List<Trade> out = new ArrayList<>();
    if (!to.isAfter(from)) {
      return out;
    }
    Instant cursor = from;
    for (int guard = 0; guard < 64 && cursor.isBefore(to) && out.size() < max; guard++) {
      Optional<Segment> seg = segmentAt(cursor);
      if (seg.isEmpty()) {
        Optional<Instant> next = nextSegmentStart(cursor, to);
        if (next.isEmpty()) {
          break;
        }
        cursor = next.get();
        continue;
      }
      Segment s = seg.get();
      Instant end = s.end().isBefore(to) ? s.end().minusNanos(1) : to;
      double lambda = p.tradeRate(s.type() == SessionType.REGULAR);
      for (long b = cursor.getEpochSecond(); b <= end.getEpochSecond() && out.size() < max; b++) {
        Instant[] arr = arrivals(tradeKey(p), b, lambda);
        for (int j = 0; j < arr.length && out.size() < max; j++) {
          Instant ts = arr[j];
          if (!ts.isAfter(from) || ts.isAfter(end) || ts.isBefore(s.start())) {
            continue;
          }
          if (scenarios.frozenSince(p.symbol(), ts).isPresent()) {
            continue;
          }
          out.add(tradeAt(p, s, ts, b, j));
        }
      }
      if (!s.end().isBefore(to)) {
        break;
      }
      cursor = s.end();
    }
    return out;
  }

  private Optional<Instant> nextSegmentStart(Instant t, Instant limit) {
    LocalDate date = Et.date(t);
    for (int i = 0; i < 15; i++, date = date.plusDays(1)) {
      Optional<TradingDay> day = calendar.day(date);
      if (day.isEmpty()) {
        continue;
      }
      Instant start = day.get().preOpenAt();
      if (start.isAfter(t)) {
        return start.isAfter(limit) ? Optional.empty() : Optional.of(start);
      }
    }
    return Optional.empty();
  }

  /** The last print at or before {@code t} (searching back through the current or last session). */
  Optional<Trade> lastTradeAt(Params p, Instant t) {
    Optional<Segment> seg = segmentAt(t);
    Instant at = t;
    if (seg.isEmpty()) {
      seg = lastSegmentBefore(t);
      if (seg.isEmpty()) {
        return Optional.empty();
      }
      at = seg.get().end().minus(1, ChronoUnit.MICROS);
    }
    Optional<Instant> frozen = scenarios.frozenSince(p.symbol(), at);
    if (frozen.isPresent()) {
      at = frozen.get().minus(1, ChronoUnit.MICROS);
    }
    for (int hop = 0; hop < 4 && seg.isPresent(); hop++) {
      Segment s = seg.get();
      double lambda = p.tradeRate(s.type() == SessionType.REGULAR);
      long first = s.start().getEpochSecond();
      for (long b = at.getEpochSecond(); b >= first && b > at.getEpochSecond() - 7200; b--) {
        Instant[] arr = arrivals(tradeKey(p), b, lambda);
        for (int j = arr.length - 1; j >= 0; j--) {
          if (!arr[j].isAfter(at) && !arr[j].isBefore(s.start())) {
            return Optional.of(tradeAt(p, s, arr[j], b, j));
          }
        }
      }
      seg = lastSegmentBefore(s.start().minusNanos(1));
      if (seg.isPresent()) {
        at = seg.get().end().minus(1, ChronoUnit.MICROS);
      }
    }
    return Optional.empty();
  }

  // ------------------------------------------------------------------------------------------- bars and stats

  Optional<Bar> minuteBar(Params p, Instant minuteStart, Instant upTo) {
    Instant end = minuteStart.plusSeconds(60).minusNanos(1);
    if (upTo.isBefore(end)) {
      end = upTo;
    }
    if (end.isBefore(minuteStart)) {
      return Optional.empty();
    }
    return aggregate(p, Timeframe.MIN1, minuteStart, tradesBetween(p, minuteStart.minusNanos(1), end, 100_000));
  }

  static Optional<Bar> aggregate(Params p, Timeframe tf, Instant start, List<Trade> trades) {
    if (trades.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal open = trades.get(0).price();
    BigDecimal close = trades.get(trades.size() - 1).price();
    BigDecimal high = open;
    BigDecimal low = open;
    long volume = 0;
    BigDecimal notional = BigDecimal.ZERO;
    for (Trade t : trades) {
      high = high.max(t.price());
      low = low.min(t.price());
      volume += t.size();
      notional = notional.add(t.price().multiply(BigDecimal.valueOf(t.size())));
    }
    BigDecimal vwap = notional.divide(BigDecimal.valueOf(volume), 4, RoundingMode.HALF_EVEN);
    return Optional.of(new Bar(p.symbol(), tf, start, open, high, low, close, volume, vwap, trades.size(), false));
  }

  /** Daily volume (shares) of a trading day. */
  long dailyVolume(Params p, LocalDate d) {
    double noise = StrictMath.exp(0.35 * SimMath.normal(SimMath.mix(p.key(), d.toEpochDay(), 5)) - 0.06);
    return StrictMath.max(100, StrictMath.round(p.advShares() * noise / 100) * 100);
  }

  /** Regular-session extremes (log) at the 256-point grid up to business time {@code tauMax}. */
  private double[] rthExtremes(Params p, int k, LocalDate d, TradingDay day, double tauMax) {
    double x0 = logOpen(p, k);
    double x1 = logCloses(p)[k];
    double sigma = 0.8 * p.sigmaD();
    long key = SimMath.mix(p.key(), d.toEpochDay(), SEG_RTH);
    double[] nodes = SimMath.level(key, depthFor(day.rthOpenAt(), day.rthCloseAt()), SAMPLE_LEVEL);
    double hi = x0;
    double lo = x0;
    int count = nodes.length - 1;
    for (int i = 0; i <= count; i++) {
      double tau = (double) i / count;
      if (tau > tauMax) {
        break;
      }
      double x = x0 + (x1 - x0) * tau + sigma * nodes[i];
      hi = StrictMath.max(hi, x);
      lo = StrictMath.min(lo, x);
    }
    return new double[] {hi, lo};
  }

  Optional<DayStats> dayStats(Params p, LocalDate date, Instant upTo) {
    Optional<TradingDay> day = calendar.day(date);
    Optional<Integer> k = dayIndex(date);
    if (day.isEmpty() || k.isEmpty() || upTo.isBefore(day.get().rthOpenAt())) {
      return Optional.empty();
    }
    TradingDay d = day.get();
    double len = Duration.between(d.rthOpenAt(), d.rthCloseAt()).toNanos();
    double u = StrictMath.min(1, Duration.between(d.rthOpenAt(), upTo).toNanos() / len);
    double tau = businessTime(u);
    double[] ext = rthExtremes(p, k.get(), date, d, tau);
    double last = u >= 1 ? logCloses(p)[k.get()] : logMidIn(p, new Segment(date, k.get(), SessionType.REGULAR,
        d.rthOpenAt(), d.rthCloseAt()), upTo);
    double f = rawFactor(p, date);
    double halfFrac = p.tierHalfSpreadBps() / 10_000;
    BigDecimal open = toTick(StrictMath.exp(logOpen(p, k.get())) * f);
    BigDecimal high = toTick(StrictMath.exp(StrictMath.max(ext[0], last)) * f * (1 + halfFrac)).max(open);
    BigDecimal low = toTick(StrictMath.exp(StrictMath.min(ext[1], last)) * f * (1 - halfFrac)).min(open);
    long volume = StrictMath.round(dailyVolume(p, date) * tau);
    return Optional.of(new DayStats(open, high, low, volume));
  }

  /** The daily bar of a completed (or in-progress, up to {@code upTo}) trading day; start = 00:00 ET. */
  Optional<Bar> dailyBar(Params p, LocalDate date, Instant upTo) {
    Optional<DayStats> stats = dayStats(p, date, upTo);
    if (stats.isEmpty()) {
      return Optional.empty();
    }
    TradingDay d = calendar.day(date).orElseThrow();
    BigDecimal close = !upTo.isBefore(d.rthCloseAt()) ? officialClose(p, date).orElseThrow()
        : priceAt(p, upTo).orElseThrow();
    DayStats s = stats.get();
    BigDecimal high = s.high().max(close);
    BigDecimal low = s.low().min(close);
    BigDecimal vwap = high.add(low).add(close).divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_EVEN);
    return Optional.of(new Bar(p.symbol(), Timeframe.DAY1, Et.startOfDay(date), s.open(), high, low, close,
        s.volume(), vwap, StrictMath.max(1, s.volume() / 150), false));
  }

  /** Trading days of the path in {@code [from, to]}. */
  List<LocalDate> tradingDays(LocalDate from, LocalDate to) {
    List<LocalDate> out = new ArrayList<>();
    for (LocalDate d : index().days()) {
      if (!d.isBefore(from) && !d.isAfter(to)) {
        out.add(d);
      }
    }
    return out;
  }
}
