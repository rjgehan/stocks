package com.stocks.marketdata.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.MoverList;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.model.Mover;
import com.stocks.contract.marketdata.model.Movers;
import com.stocks.contract.jobs.PeriodicTask;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top movers (§15.5): the provider's screener (Alpaca movers and most-actives; the SIM computes its own), refreshed
 * every 60 s during sessions ({@code MOVERS_REFRESH}, a {@link PeriodicTask}) and cached; when the screener is not
 * available the list is computed from the universe quotes the poller keeps current.
 */
public class MoversService implements PeriodicTask {

  private static final Logger log = LoggerFactory.getLogger(MoversService.class);
  static final int TOP = 50;

  private final MarketDataProvider provider;
  private final MarketFeed feed;
  private final AssetService assets;
  private final MarketCalendar calendar;
  private final TimeSource time;
  private final Supplier<Boolean> screenerAvailable;
  private final Cache<MoverList, Movers> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60))
      .build();

  public MoversService(MarketDataProvider provider, MarketFeed feed, AssetService assets, MarketCalendar calendar,
                       TimeSource time, Supplier<Boolean> screenerAvailable) {
    this.provider = provider;
    this.feed = feed;
    this.assets = assets;
    this.calendar = calendar;
    this.time = time;
    this.screenerAvailable = screenerAvailable;
  }

  @Override
  public String name() {
    return "MOVERS_REFRESH";
  }

  @Override
  public Duration interval() {
    return Duration.ofSeconds(60);
  }

  @Override
  public boolean sessionsOnly() {
    return true;
  }

  @Override
  public void tick() {
    for (MoverList l : MoverList.values()) {
      cache.put(l, load(l));
    }
  }

  public Movers movers(MoverList list, int limit) {
    Movers m = cache.get(list, this::load);
    List<Mover> items = m.items().size() > limit ? m.items().subList(0, limit) : m.items();
    return new Movers(m.list(), items, m.asOf(), m.source());
  }

  Movers load(MoverList list) {
    if (screenerAvailable.get() && provider.capabilities().movers()) {
      try {
        Movers m = provider.movers(list, TOP);
        if (!m.items().isEmpty()) {
          return list == MoverList.ACTIVES ? withPrices(m) : m;
        }
      } catch (ProviderException | UnsupportedOperationException e) {
        log.debug("movers {} from {} failed: {}", list, provider.id(), e.getMessage());
      }
    }
    return computed(list);
  }

  /** Most-actives carry only volume: fill price and change from the quote book. */
  private Movers withPrices(Movers m) {
    List<String> symbols = m.items().stream().map(Mover::symbol).toList();
    Map<String, QuoteView> quotes = feed.quotes(symbols);   // at most 50: one snapshot call
    List<Mover> items = new ArrayList<>();
    for (Mover mv : m.items()) {
      QuoteView q = quotes.get(mv.symbol());
      if (q != null && q.last() != null) {
        items.add(new Mover(mv.symbol(), q.last(), q.change(), q.changePct(), mv.volume()));
      }
    }
    return new Movers(m.list(), items, m.asOf(), m.source());
  }

  /** The fallback: universe stocks (not ETFs) ranked from current quotes. */
  Movers computed(MoverList list) {
    List<String> symbols = assets.universe().entries().stream().filter(e -> e.type() != AssetType.ETF)
        .map(Universe.Entry::symbol).toList();
    Map<String, QuoteView> quotes = feed.peek(symbols);
    List<Mover> all = new ArrayList<>();
    for (QuoteView q : quotes.values()) {
      if (q.last() != null && q.changePct() != null) {
        all.add(new Mover(q.symbol(), q.last(), q.change(), q.changePct(), q.volume()));
      }
    }
    Comparator<Mover> order = switch (list) {
      case GAINERS -> Comparator.comparing(Mover::changePct).reversed();
      case LOSERS -> Comparator.comparing(Mover::changePct);
      case ACTIVES -> Comparator.comparing((Mover m) -> m.volume() == null ? 0L : m.volume()).reversed();
    };
    List<Mover> items = all.stream()
        .filter(m -> list != MoverList.GAINERS || m.changePct().signum() > 0)
        .filter(m -> list != MoverList.LOSERS || m.changePct().signum() < 0)
        .sorted(order.thenComparing(Mover::symbol)).limit(TOP).toList();
    return new Movers(list, items, time.now(), "computed");
  }
}
