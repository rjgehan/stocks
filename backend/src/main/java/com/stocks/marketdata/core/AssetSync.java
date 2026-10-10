package com.stocks.marketdata.core;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.marketdata.spi.BarQuery;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the {@code asset} table current (§15.5 {@code AssetSyncJob}). {@link #seed} runs synchronously at boot, so
 * every universe symbol (and the SIM's {@code SIMA}–{@code SIMZ}) has a row before the first order references it;
 * {@link #sync} pulls the provider's asset list (Alpaca: tradable US equities on the listed exchanges, with the
 * {@code fractionable} flag), merges Finnhub security types, and sets liquidity tiers from the 20-day average dollar
 * volume of SIP daily bars for the universe and held symbols (everything else SMALL).
 */
public class AssetSync {

  private static final Logger log = LoggerFactory.getLogger(AssetSync.class);
  static final int ADV_DAYS = 20;

  private final AssetService assets;
  private final MarketDataProvider primary;
  private final MarketDataProvider typesOrNull;
  private final boolean simulated;
  private final List<Asset> seedAssets;
  private final TrackedSymbols tracked;
  private final MarketCalendar calendar;
  private final TimeSource time;

  public AssetSync(AssetService assets, MarketDataProvider primary, MarketDataProvider typesOrNull, boolean simulated,
                   List<Asset> seedAssets, TrackedSymbols tracked, MarketCalendar calendar, TimeSource time) {
    this.assets = assets;
    this.primary = primary;
    this.typesOrNull = typesOrNull;
    this.simulated = simulated;
    this.seedAssets = List.copyOf(seedAssets);
    this.tracked = tracked;
    this.calendar = calendar;
    this.time = time;
  }

  /** Inserts the seed assets that have no row yet, then loads the table into memory. */
  public int seed() {
    assets.reload();
    int n = 0;
    try {
      n = assets.ensure(seedAssets, simulated ? "simulated" : "manual");
      if (n > 0) {
        log.info("Assets: seeded {} missing symbols ({} known)", n, assets.size());
      }
    } catch (RuntimeException e) {
      log.warn("Assets: could not seed the asset table: {}", e.toString());
    }
    return n;
  }

  /** The full provider sync (the SIM only re-seeds). Returns the number of assets written. */
  public int sync() {
    if (simulated) {
      return seed();
    }
    List<Asset> listed;
    try {
      listed = primary.assets();
    } catch (ProviderException | UnsupportedOperationException e) {
      log.warn("Asset sync: {} asset list unavailable: {}", primary.id(), e.getMessage());
      return 0;
    }
    Map<String, AssetType> types = Map.of();
    if (typesOrNull != null) {
      try {
        types = typesOrNull.assetTypes();
      } catch (ProviderException | UnsupportedOperationException e) {
        log.warn("Asset sync: {} security types unavailable: {}", typesOrNull.id(), e.getMessage());
      }
    }
    Set<String> advSymbols = new TreeSet<>(assets.universe().symbols());
    advSymbols.addAll(tracked.held());
    Map<String, BigDecimal> adv = averageDollarVolume(advSymbols);
    List<Asset> merged = AssetService.merge(listed, types, adv, assets.universe());
    assets.save(merged, primary.id(), adv);
    log.info("Asset sync: {} assets from {} ({} listed, {} with ADV)", merged.size(), primary.id(), listed.size(),
        adv.size());
    return merged.size();
  }

  /** 20-day average dollar volume (close × volume) from SIP daily bars, in batches of 100 symbols. */
  Map<String, BigDecimal> averageDollarVolume(Set<String> symbols) {
    Map<String, BigDecimal> out = new HashMap<>();
    Instant now = time.now();
    LocalDate end = calendar.previousTradingDay(Et.date(now).plusDays(1));
    LocalDate start = end;
    for (int i = 0; i < ADV_DAYS + 2; i++) {
      start = calendar.previousTradingDay(start);
    }
    List<String> all = new ArrayList<>(symbols);
    for (int i = 0; i < all.size(); i += 100) {
      Set<String> batch = Set.copyOf(all.subList(i, Math.min(all.size(), i + 100)));
      Map<String, List<Bar>> bars;
      try {
        bars = primary.bars(new BarQuery(batch, Timeframe.DAY1, Et.startOfDay(start), Et.startOfDay(end), AdjustmentMode.RAW,
            false, 10_000, "sip"), RefreshPriority.BACKGROUND);
      } catch (ProviderException | UnsupportedOperationException e) {
        log.warn("Asset sync: ADV bars failed: {}", e.getMessage());
        continue;
      }
      bars.forEach((sym, list) -> {
        List<Bar> lastN = list.size() > ADV_DAYS ? list.subList(list.size() - ADV_DAYS, list.size()) : list;
        if (lastN.isEmpty()) {
          return;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Bar b : lastN) {
          total = total.add(b.close().multiply(BigDecimal.valueOf(b.volume())));
        }
        out.put(sym, total.divide(BigDecimal.valueOf(lastN.size()), 2, RoundingMode.HALF_EVEN));
      });
    }
    return out;
  }
}
