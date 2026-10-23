package com.stocks.marketdata.core;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.Crypto;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.model.Asset;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assets and symbol search (§15.5): an in-memory copy of the {@code asset} table, loaded at boot and replaced after
 * every sync. Search ranks exact symbol > symbol prefix > name word prefix > substring; inside a rank the bundled
 * universe (popular names) comes first, then shorter symbols.
 */
public class AssetService {

  private static final Logger log = LoggerFactory.getLogger(AssetService.class);

  /** Exchanges kept from the Alpaca asset list (§15.5). */
  public static final Set<String> EXCHANGES = Set.of("NYSE", "NASDAQ", "ARCA", "NYSEARCA", "AMEX", "BATS");
  public static final BigDecimal MEGA_ADV = new BigDecimal("500000000");
  public static final BigDecimal MID_ADV = new BigDecimal("20000000");

  private final AssetRepository repository;
  private final Universe universe;
  private final TimeSource time;
  private volatile Map<String, Asset> bySymbol = Map.of();
  private volatile List<Asset> searchable = List.of();

  public AssetService(AssetRepository repository, Universe universe, TimeSource time) {
    this.repository = repository;
    this.universe = universe;
    this.time = time;
  }

  public Universe universe() {
    return universe;
  }

  /** The seeded crypto pairs (§29) as assets: tradable, fractionable, exchange {@code CRYPTO}, type CRYPTO. */
  public static List<Asset> cryptoAssets() {
    return Crypto.PAIRS.stream().map(p -> new Asset(p.symbol(), p.name(), Crypto.EXCHANGE, AssetType.CRYPTO, true,
        true, false, false, false, p.tier(), true)).toList();
  }

  /** Loads the table into memory. */
  public synchronized void reload() {
    Map<String, Asset> m = new HashMap<>();
    try {
      for (AssetRepository.Row r : repository.loadAll()) {
        m.put(r.asset().symbol(), r.asset());
      }
    } catch (RuntimeException e) {
      log.warn("Assets: could not read the asset table ({}); using the bundled universe", e.toString());
      universe.entries().forEach(en -> m.put(en.symbol(), en.asset()));
    }
    bySymbol = Map.copyOf(m);
    // option contracts (§30) are reached through their underlying's chain, not symbol search
    searchable = m.values().stream().filter(a -> a.active() && a.tradable() && a.type() != AssetType.OPTION).toList();
  }

  /** Upserts {@code assets} (source {@code simulated}, {@code alpaca} or {@code manual}) and reloads. */
  public synchronized void save(List<Asset> assets, String source, Map<String, BigDecimal> adv) {
    repository.upsert(assets, source, adv, time.now());
    reload();
  }

  /** Ensures every given asset has a row (insert-only semantics for missing symbols), then reloads. */
  public synchronized int ensure(List<Asset> assets, String source) {
    Set<String> existing = bySymbol.keySet();
    List<Asset> missing = assets.stream().filter(a -> !existing.contains(a.symbol())).toList();
    if (!missing.isEmpty()) {
      repository.upsert(missing, source, Map.of(), time.now());
      reload();
    }
    return missing.size();
  }

  public long countBySource(String source) {
    return repository.countBySource(source);
  }

  public Optional<Asset> asset(String symbol) {
    if (symbol == null) {
      return Optional.empty();
    }
    Asset a = bySymbol.get(symbol);
    if (a != null && a.type() == AssetType.OPTION) {
      return option(symbol).or(() -> Optional.of(a));   // a stored contract stops being tradable once it expired
    }
    return a != null ? Optional.of(a) : option(symbol);
  }

  /**
   * §30: an option contract on a known, active stock or ETF, as an asset: type OPTION, exchange OPRA, whole contracts,
   * tradable and active until its expiration date has passed. A contract gets an {@code asset} row when it is first
   * ordered (the engine inserts it for the order's foreign key); this synthesizes it, so chains and previews need no
   * writes, and a stored contract is read through it too, so it stops being tradable once it expired.
   */
  Optional<Asset> option(String symbol) {
    Optional<OptionSymbol.Contract> c = OptionSymbol.parse(symbol);
    if (c.isEmpty() || !c.get().symbol().equals(symbol)) {
      return Optional.empty();
    }
    Asset u = bySymbol.get(c.get().underlying());
    if (u == null || !u.active() || u.type() == AssetType.CRYPTO || u.type() == AssetType.OPTION) {
      return Optional.empty();
    }
    boolean live = !c.get().expiration().isBefore(Et.date(time.now()));
    return Optional.of(new Asset(symbol, c.get().label(), OptionSymbol.EXCHANGE, AssetType.OPTION, live, false, false,
        false, false, LiquidityTier.SMALL, live));
  }


  public LiquidityTier tier(String symbol) {
    Asset a = bySymbol.get(symbol);
    return a == null || a.tier() == null ? LiquidityTier.SMALL : a.tier();
  }

  public int size() {
    return bySymbol.size();
  }

  public List<Asset> all() {
    return List.copyOf(bySymbol.values());
  }

  public List<Asset> search(String query, int limit) {
    String q = query == null ? "" : Crypto.fromPath(query.trim().toUpperCase(Locale.ROOT));   // BTC-USD → BTC/USD
    if (q.isEmpty() || limit <= 0) {
      return List.of();
    }
    record Hit(Asset asset, int rank) {}
    List<Hit> hits = new ArrayList<>();
    for (Asset a : searchable) {
      int rank = rank(a, q);
      if (rank < Integer.MAX_VALUE) {
        hits.add(new Hit(a, rank));
      }
    }
    hits.sort(Comparator.comparingInt(Hit::rank)
        .thenComparing(h -> universe.contains(h.asset().symbol()) || Crypto.isCrypto(h.asset().symbol()) ? 0 : 1)
        .thenComparingInt(h -> h.asset().symbol().length())
        .thenComparing(h -> h.asset().symbol()));
    return hits.stream().limit(limit).map(Hit::asset).toList();
  }

  /** 0 exact symbol, 1 symbol prefix, 2 name word prefix, 3 substring; MAX_VALUE = no match. */
  static int rank(Asset a, String q) {
    String sym = a.symbol();
    if (sym.equals(q)) {
      return 0;
    }
    if (sym.startsWith(q)) {
      return 1;
    }
    String name = a.name() == null ? "" : a.name().toUpperCase(Locale.ROOT);
    for (String word : name.split("[^A-Z0-9&]+")) {
      if (!word.isEmpty() && word.startsWith(q)) {
        return 2;
      }
    }
    if (q.length() >= 2 && (sym.contains(q) || name.contains(q))) {
      return 3;
    }
    return Integer.MAX_VALUE;
  }

  /** Tier from a 20-day average dollar volume (§12.3 table). */
  public static LiquidityTier tierFor(BigDecimal adv) {
    if (adv == null) {
      return LiquidityTier.SMALL;
    }
    if (adv.compareTo(MEGA_ADV) >= 0) {
      return LiquidityTier.MEGA;
    }
    return adv.compareTo(MID_ADV) >= 0 ? LiquidityTier.MID : LiquidityTier.SMALL;
  }

  /**
   * Merges a provider asset list (§15.5 AssetSyncJob): keeps tradable symbols on the listed exchanges, applies
   * Finnhub security types, tiers from ADV (else the universe tier, else SMALL), and keeps universe names when the
   * provider has none. Universe symbols the provider does not list are kept as they are.
   */
  public static List<Asset> merge(List<Asset> provider, Map<String, AssetType> types, Map<String, BigDecimal> adv,
                                  Universe universe) {
    Map<String, Asset> out = new LinkedHashMap<>();
    for (Asset a : provider) {
      if (a.symbol() == null || !a.tradable() || a.exchange() == null || !EXCHANGES.contains(a.exchange())) {
        continue;
      }
      Optional<Universe.Entry> u = universe.get(a.symbol());
      AssetType type = types.getOrDefault(a.symbol(), u.map(Universe.Entry::type).orElse(AssetType.UNKNOWN));
      LiquidityTier tier = adv.containsKey(a.symbol()) ? tierFor(adv.get(a.symbol()))
          : u.map(Universe.Entry::tier).orElse(LiquidityTier.SMALL);
      out.put(a.symbol(), new Asset(a.symbol(), a.name(), a.exchange(), type, a.tradable(), a.fractionable(),
          a.shortable(), a.easyToBorrow(), a.marginable(), tier, a.active()));
    }
    return new ArrayList<>(out.values());
  }
}
