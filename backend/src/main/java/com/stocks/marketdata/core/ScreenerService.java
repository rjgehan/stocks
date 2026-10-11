package com.stocks.marketdata.core;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.ScreenPreset;
import com.stocks.contract.marketdata.ScreenQuery;
import com.stocks.contract.marketdata.ScreenResult;
import com.stocks.contract.marketdata.ScreenSort;
import com.stocks.contract.marketdata.Sector;
import com.stocks.contract.marketdata.SortOrder;
import com.stocks.contract.marketdata.model.Asset;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * The screener (§15.10): the universe with the latest quotes ({@link MarketFeed#peek}, never waiting on a provider,
 * split-adjusted) and the {@link ScreenerStats}, computed at most every {@link #TABLE_TTL} and then filtered, sorted
 * and paged in memory. Outside sessions (or after a restart) it asks the feed for one background snapshot pass,
 * at most every {@link #WARM_EVERY}.
 */
public class ScreenerService {

  static final Duration TABLE_TTL = Duration.ofSeconds(5);
  static final Duration WARM_EVERY = Duration.ofMinutes(5);
  static final Duration WARMING_FOR = Duration.ofSeconds(20);
  static final BigDecimal BILLION = new BigDecimal("1000000000");
  static final BigDecimal EPSILON_PCT = new BigDecimal("0.000001");
  static final Set<AssetType> STOCKS = EnumSet.of(AssetType.COMMON_STOCK, AssetType.ADR);

  /** The filters, sort and order a screen runs with (preset values replaced by explicit ones). */
  record Effective(BigDecimal minPrice, BigDecimal maxPrice, BigDecimal minMarketCap, BigDecimal maxMarketCap,
                   BigDecimal minChangePct, BigDecimal maxChangePct, Long minVolume, Long minAvgVolume,
                   BigDecimal minRelVolume, Set<Sector> sectors, Set<AssetType> types, ScreenSort sort,
                   SortOrder order) {}

  /** {@code stats}: the statistics snapshot the rows were built from (a new snapshot rebuilds the table at once). */
  private record Table(List<ScreenResult.Row> rows, int universe, Instant asOf, long builtAtNanos,
                       Map<String, ScreenerStats.Stat> stats) {}

  private final MarketFeed feed;
  private final AssetService assets;
  private final Sectors sectors;
  private final ScreenerStats stats;
  private final UnaryOperator<QuoteView> splitAdjust;
  private final SplitFactor splitFactor;
  private final TimeSource time;
  private final boolean simulated;
  private final LongSupplier nanos;
  private volatile Table table;
  private volatile long warmedAtNanos;
  private volatile boolean warmedOnce;

  /**
   * The share-count factor of a symbol's applied splits with {@code since < ex_date ≤ asOf} (§14,
   * {@code price_adjustment}): {@code {Π new_rate, Π old_rate}}, so {@code shares × [0] ÷ [1]}; null when none applies.
   */
  @FunctionalInterface
  public interface SplitFactor {
    SplitFactor NONE = (symbol, since, asOf) -> null;

    BigDecimal[] of(String symbol, LocalDate since, LocalDate asOf);
  }

  public ScreenerService(MarketFeed feed, AssetService assets, Sectors sectors, ScreenerStats stats,
                         UnaryOperator<QuoteView> splitAdjust, SplitFactor splitFactor, TimeSource time,
                         boolean simulated) {
    this(feed, assets, sectors, stats, splitAdjust, splitFactor, time, simulated, System::nanoTime);
  }

  ScreenerService(MarketFeed feed, AssetService assets, Sectors sectors, ScreenerStats stats,
                  UnaryOperator<QuoteView> splitAdjust, SplitFactor splitFactor, TimeSource time, boolean simulated,
                  LongSupplier nanos) {
    this.feed = feed;
    this.assets = assets;
    this.sectors = sectors;
    this.stats = stats;
    this.splitAdjust = splitAdjust;
    this.splitFactor = splitFactor == null ? SplitFactor.NONE : splitFactor;
    this.time = time;
    this.simulated = simulated;
    this.nanos = nanos;
  }

  /** The screened universe: active, tradable universe symbols (the asset row wins over the bundled entry). */
  public List<Asset> universe() {
    List<Asset> out = new ArrayList<>();
    for (Universe.Entry e : assets.universe().entries()) {
      Asset a = assets.asset(e.symbol()).orElse(e.asset());
      if (a.active() && a.tradable()) {
        out.add(a);
      }
    }
    out.sort(Comparator.comparing(Asset::symbol));
    return out;
  }

  public ScreenResult screen(ScreenQuery q) {
    Table t = table();
    Effective e = effective(q);
    List<ScreenResult.Row> matches = new ArrayList<>();
    for (ScreenResult.Row r : t.rows()) {
      if (matches(r, e)) {
        matches.add(r);
      }
    }
    matches.sort(comparator(e.sort(), e.order()));
    int from = Math.min(q.offset(), matches.size());
    int to = Math.min(matches.size(), from + q.limit());
    Integer next = to < matches.size() ? to : null;
    int priced = t.rows().size();
    int withCap = (int) t.rows().stream().filter(r -> r.marketCap() != null).count();
    int withAvg = (int) t.rows().stream().filter(r -> r.avgVolume() != null).count();
    boolean warming = stats.ensureLocal() || stats.refreshing() && withAvg == 0
        || !simulated && warmedOnce && nanos.getAsLong() - warmedAtNanos < WARMING_FOR.toNanos()
        && priced < t.universe();
    return new ScreenResult(q.preset(), e.sort(), e.order(), matches.size(), matches.subList(from, to), next, t.asOf(),
        warming, new ScreenResult.Coverage(t.universe(), priced, withCap, withAvg));
  }

  // ------------------------------------------------------------------------------------------- the table

  private Table table() {
    Table t = table;
    if (fresh(t)) {
      return t;
    }
    synchronized (this) {
      t = table;
      if (fresh(t)) {
        return t;
      }
      t = build();
      table = t;
      return t;
    }
  }

  /** Built less than {@link #TABLE_TTL} ago from the current statistics snapshot. */
  private boolean fresh(Table t) {
    return t != null && nanos.getAsLong() - t.builtAtNanos() < TABLE_TTL.toNanos() && t.stats() == stats.stats();
  }

  private Table build() {
    List<Asset> universe = universe();
    List<String> symbols = universe.stream().map(Asset::symbol).toList();
    if (!simulated && (!warmedOnce || nanos.getAsLong() - warmedAtNanos >= WARM_EVERY.toNanos())) {
      warmedOnce = true;
      warmedAtNanos = nanos.getAsLong();
      feed.warm(symbols);                                   // background, BACKGROUND priority, throttled here
    }
    Map<String, ScreenerStats.Stat> st = stats.stats();
    Map<String, QuoteView> quotes = feed.peek(symbols);
    LocalDate today = Et.date(time.now());
    List<ScreenResult.Row> rows = new ArrayList<>();
    for (Asset a : universe) {
      QuoteView raw = quotes.get(a.symbol());
      QuoteView qv = raw == null ? null : splitAdjust.apply(raw);
      if (qv == null || qv.last() == null || qv.last().signum() <= 0 || qv.quality() == DataQuality.NONE) {
        continue;
      }
      rows.add(row(a, qv, st.get(a.symbol()), today));
    }
    return new Table(List.copyOf(rows), universe.size(), time.now(), nanos.getAsLong(), st);
  }

  private ScreenResult.Row row(Asset a, QuoteView q, ScreenerStats.Stat s, LocalDate today) {
    BigDecimal price = q.last();
    BigDecimal marketCap = null;
    if (a.type() != AssetType.ETF && s != null) {
      if (s.sharesOutstanding() != null && s.sharesOutstanding().signum() > 0) {
        // the stored count is pre-split if a split took effect after the profile was fetched: rescale it (§15.10)
        BigDecimal[] f = factor(a.symbol(), s.profileAt(), today);
        BigDecimal cap = s.sharesOutstanding().multiply(price);
        marketCap = f == null ? cap.setScale(0, RoundingMode.HALF_EVEN)
            : cap.multiply(f[0]).divide(f[1], 0, RoundingMode.HALF_EVEN);
      } else if (s.marketCap() != null && s.marketCap().signum() > 0) {
        marketCap = s.marketCap().setScale(0, RoundingMode.HALF_EVEN);      // a dollar value: a split keeps it
      }
    }
    BigDecimal[] vf = s == null ? null : factor(a.symbol(), s.volumeAt(), today);
    Long sip = s == null ? null : scaled(s.avgVolume(), vf);
    Long iex = s == null ? null : scaled(s.avgVolumeIex(), vf);
    Long avg = sip != null ? sip : iex;
    // the same basis or none (§15.10): an IEX-only volume is never divided by the SIP average
    Long basis = q.volumeIsPartial() ? iex : sip;
    BigDecimal rel = relVolume(q.volume(), basis);
    Sector sector = a.type() == AssetType.ETF ? null
        : sectors.of(a.symbol(), s == null ? null : s.industry()).orElse(null);
    return new ScreenResult.Row(a.symbol(), a.name(), a.type(), a.exchange(), sector, price, q.change(), q.changePct(),
        q.volume(), q.volumeIsPartial(), avg, rel, marketCap, q.quality(), q.asOf());
  }

  /** The split factor since {@code fetchedAt}'s ET date, or null (never fetched, or no split since). */
  private BigDecimal[] factor(String symbol, Instant fetchedAt, LocalDate today) {
    if (fetchedAt == null) {
      return null;
    }
    BigDecimal[] f = splitFactor.of(symbol, Et.date(fetchedAt), today);
    return f == null || f.length != 2 || f[0] == null || f[1] == null || f[0].signum() <= 0 || f[1].signum() <= 0
        ? null : f;
  }

  /** A share volume on today's share basis (HALF_EVEN). */
  private static Long scaled(Long volume, BigDecimal[] f) {
    if (volume == null || f == null) {
      return volume;
    }
    return BigDecimal.valueOf(volume).multiply(f[0]).divide(f[1], 0, RoundingMode.HALF_EVEN).longValue();
  }

  static BigDecimal relVolume(Long volume, Long average) {
    if (volume == null || average == null || average <= 0) {
      return null;
    }
    return BigDecimal.valueOf(volume).divide(BigDecimal.valueOf(average), 2, RoundingMode.HALF_EVEN);
  }

  // ------------------------------------------------------------------------------------------- filters

  /** The preset's values, each replaced by the query's own when the query sets it (§15.10). */
  static Effective effective(ScreenQuery q) {
    BigDecimal minPrice = null;
    BigDecimal maxPrice = null;
    BigDecimal minCap = null;
    BigDecimal maxCap = null;
    BigDecimal minChg = null;
    BigDecimal maxChg = null;
    Long minVol = null;
    Long minAvg = null;
    BigDecimal minRel = null;
    Set<AssetType> types = Set.of();
    ScreenSort sort = null;
    SortOrder order = null;
    if (q.preset() != null) {
      switch (q.preset()) {
        case TOP_GAINERS -> {
          types = STOCKS;
          minChg = EPSILON_PCT;
          sort = ScreenSort.CHANGE_PCT;
          order = SortOrder.DESC;
        }
        case TOP_LOSERS -> {
          types = STOCKS;
          maxChg = EPSILON_PCT.negate();
          sort = ScreenSort.CHANGE_PCT;
          order = SortOrder.ASC;
        }
        case MOST_ACTIVE -> {
          types = STOCKS;
          sort = ScreenSort.VOLUME;
          order = SortOrder.DESC;
        }
        case UNUSUAL_VOLUME -> {
          types = STOCKS;
          minRel = new BigDecimal("2");
          minAvg = 500_000L;
          sort = ScreenSort.REL_VOLUME;
          order = SortOrder.DESC;
        }
        case LARGE_CAPS_UNDER_50 -> {
          minCap = BILLION.multiply(BigDecimal.TEN);
          maxPrice = new BigDecimal("50");
          sort = ScreenSort.MARKET_CAP;
          order = SortOrder.DESC;
        }
        case MEGA_CAPS -> {
          minCap = BILLION.multiply(new BigDecimal("200"));
          sort = ScreenSort.MARKET_CAP;
          order = SortOrder.DESC;
        }
        case ETFS -> {
          types = EnumSet.of(AssetType.ETF);
          sort = ScreenSort.VOLUME;
          order = SortOrder.DESC;
        }
        default -> throw new IllegalStateException("unknown preset " + q.preset());
      }
    }
    ScreenSort s = q.sort() != null ? q.sort() : sort != null ? sort : ScreenSort.MARKET_CAP;
    SortOrder o = q.order() != null ? q.order()
        : q.sort() == null && order != null ? order : s == ScreenSort.SYMBOL ? SortOrder.ASC : SortOrder.DESC;
    return new Effective(or(q.minPrice(), minPrice), or(q.maxPrice(), maxPrice), or(q.minMarketCap(), minCap),
        or(q.maxMarketCap(), maxCap), or(q.minChangePct(), minChg), or(q.maxChangePct(), maxChg),
        or(q.minVolume(), minVol), or(q.minAvgVolume(), minAvg), or(q.minRelVolume(), minRel), q.sectors(),
        q.types().isEmpty() ? types : q.types(), s, o);
  }

  private static <T> T or(T explicit, T preset) {
    return explicit != null ? explicit : preset;
  }

  static boolean matches(ScreenResult.Row r, Effective e) {
    return atLeast(r.price(), e.minPrice()) && atMost(r.price(), e.maxPrice())
        && atLeast(r.marketCap(), e.minMarketCap()) && atMost(r.marketCap(), e.maxMarketCap())
        && atLeast(r.changePct(), e.minChangePct()) && atMost(r.changePct(), e.maxChangePct())
        && atLeast(r.volume(), e.minVolume()) && atLeast(r.avgVolume(), e.minAvgVolume())
        && atLeast(r.relVolume(), e.minRelVolume())
        && (e.sectors().isEmpty() || r.sector() != null && e.sectors().contains(r.sector()))
        && (e.types().isEmpty() || e.types().contains(r.type()));
  }

  private static <T extends Comparable<T>> boolean atLeast(T v, T min) {
    return min == null || v != null && v.compareTo(min) >= 0;
  }

  private static <T extends Comparable<T>> boolean atMost(T v, T max) {
    return max == null || v != null && v.compareTo(max) <= 0;
  }

  /** By the sort key in the given order, nulls last in both orders, ties by symbol (ascending). */
  static Comparator<ScreenResult.Row> comparator(ScreenSort sort, SortOrder order) {
    Comparator<ScreenResult.Row> byKey = switch (sort) {
      case SYMBOL -> by(ScreenResult.Row::symbol, order);
      case PRICE -> by(ScreenResult.Row::price, order);
      case CHANGE_PCT -> by(ScreenResult.Row::changePct, order);
      case VOLUME -> by(ScreenResult.Row::volume, order);
      case AVG_VOLUME -> by(ScreenResult.Row::avgVolume, order);
      case REL_VOLUME -> by(ScreenResult.Row::relVolume, order);
      case MARKET_CAP -> by(ScreenResult.Row::marketCap, order);
    };
    return byKey.thenComparing(ScreenResult.Row::symbol);
  }

  private static <T extends Comparable<? super T>> Comparator<ScreenResult.Row> by(
      Function<ScreenResult.Row, T> key, SortOrder order) {
    Comparator<T> natural = Comparator.naturalOrder();
    return Comparator.comparing(key, Comparator.nullsLast(order == SortOrder.DESC ? natural.reversed() : natural));
  }
}
