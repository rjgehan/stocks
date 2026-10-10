package com.stocks.marketdata.core;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.DataQuality;
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

  private record Table(List<ScreenResult.Row> rows, int universe, Instant asOf, long builtAtNanos) {}

  private final MarketFeed feed;
  private final AssetService assets;
  private final Sectors sectors;
  private final ScreenerStats stats;
  private final UnaryOperator<QuoteView> splitAdjust;
  private final TimeSource time;
  private final boolean simulated;
  private final LongSupplier nanos;
  private volatile Table table;
  private volatile long warmedAtNanos;
  private volatile boolean warmedOnce;

  public ScreenerService(MarketFeed feed, AssetService assets, Sectors sectors, ScreenerStats stats,
                         UnaryOperator<QuoteView> splitAdjust, TimeSource time, boolean simulated) {
    this(feed, assets, sectors, stats, splitAdjust, time, simulated, System::nanoTime);
  }

  ScreenerService(MarketFeed feed, AssetService assets, Sectors sectors, ScreenerStats stats,
                  UnaryOperator<QuoteView> splitAdjust, TimeSource time, boolean simulated, LongSupplier nanos) {
    this.feed = feed;
    this.assets = assets;
    this.sectors = sectors;
    this.stats = stats;
    this.splitAdjust = splitAdjust;
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
    long now = nanos.getAsLong();
    if (t != null && now - t.builtAtNanos() < TABLE_TTL.toNanos()) {
      return t;
    }
    synchronized (this) {
      t = table;
      if (t != null && nanos.getAsLong() - t.builtAtNanos() < TABLE_TTL.toNanos()) {
        return t;
      }
      t = build();
      table = t;
      return t;
    }
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
    List<ScreenResult.Row> rows = new ArrayList<>();
    for (Asset a : universe) {
      QuoteView raw = quotes.get(a.symbol());
      QuoteView qv = raw == null ? null : splitAdjust.apply(raw);
      if (qv == null || qv.last() == null || qv.last().signum() <= 0 || qv.quality() == DataQuality.NONE) {
        continue;
      }
      rows.add(row(a, qv, st.get(a.symbol())));
    }
    return new Table(List.copyOf(rows), universe.size(), time.now(), nanos.getAsLong());
  }

  private ScreenResult.Row row(Asset a, QuoteView q, ScreenerStats.Stat s) {
    BigDecimal price = q.last();
    BigDecimal marketCap = null;
    if (a.type() != AssetType.ETF && s != null) {
      if (s.sharesOutstanding() != null && s.sharesOutstanding().signum() > 0) {
        marketCap = s.sharesOutstanding().multiply(price).setScale(0, RoundingMode.HALF_EVEN);
      } else if (s.marketCap() != null && s.marketCap().signum() > 0) {
        marketCap = s.marketCap().setScale(0, RoundingMode.HALF_EVEN);
      }
    }
    Long avg = s == null ? null : s.avgVolume() != null ? s.avgVolume() : s.avgVolumeIex();
    Long basis = s == null ? null : q.volumeIsPartial() && s.avgVolumeIex() != null ? s.avgVolumeIex() : s.avgVolume();
    BigDecimal rel = relVolume(q.volume(), basis);
    Sector sector = a.type() == AssetType.ETF ? null
        : sectors.of(a.symbol(), s == null ? null : s.industry()).orElse(null);
    return new ScreenResult.Row(a.symbol(), a.name(), a.type(), a.exchange(), sector, price, q.change(), q.changePct(),
        q.volume(), q.volumeIsPartial(), avg, rel, marketCap, q.quality(), q.asOf());
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
