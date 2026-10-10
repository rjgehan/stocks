package com.stocks.marketdata.spi;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.MoverList;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.CorporateActionData;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.contract.marketdata.model.MarketEvent;
import com.stocks.contract.marketdata.model.Movers;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.RecommendationTrend;
import com.stocks.contract.marketdata.model.Snapshot;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.contract.marketdata.model.TradingDayData;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A market-data source (§15.3, CONTRACT within WS2): {@code alpaca}, {@code finnhub} or {@code simulated}. Every
 * method may throw {@link ProviderException}; unsupported capabilities throw {@link UnsupportedOperationException}.
 * Only the backend talks to providers; raw payloads never reach clients (§15.1).
 */
public interface MarketDataProvider {

  String id();

  Capabilities capabilities();

  default List<Asset> assets() {
    throw unsupported("assets");
  }

  default List<TradingDayData> calendar(LocalDate from, LocalDate to) {
    throw unsupported("calendar");
  }

  default Map<String, Snapshot> snapshots(Set<String> symbols) {
    throw unsupported("snapshots");
  }

  /** {@link #snapshots(Set)} charged to a REST priority class (§15.5 {@code ProviderRateLimiter}). */
  default Map<String, Snapshot> snapshots(Set<String> symbols, RefreshPriority priority) {
    return snapshots(symbols);
  }

  /** {@link #bars(BarQuery)} charged to a REST priority class. */
  default Map<String, List<Bar>> bars(BarQuery q, RefreshPriority priority) {
    return bars(q);
  }

  default Map<String, Quote> latestQuotes(Set<String> symbols) {
    throw unsupported("quotes");
  }

  default Map<String, Trade> latestTrades(Set<String> symbols) {
    throw unsupported("trades");
  }

  default Map<String, List<Bar>> bars(BarQuery q) {
    throw unsupported("bars");
  }

  default List<NewsItem> news(Set<String> symbols, int limit, Instant before) {
    throw unsupported("news");
  }

  default List<CorporateActionData> corporateActions(Set<String> symbols, LocalDate from, LocalDate to) {
    throw unsupported("corporateActions");
  }

  default Optional<Fundamentals> fundamentals(String symbol) {
    throw unsupported("fundamentals");
  }

  default Movers movers(MoverList list, int top) {
    throw unsupported("movers");
  }

  default Map<String, AssetType> assetTypes() {
    throw unsupported("assetTypes");
  }

  /** Phase 2 (§15.10): monthly analyst recommendation trends, newest first (Finnhub {@code /stock/recommendation}). */
  default List<RecommendationTrend> recommendations(String symbol) {
    throw unsupported("recommendations");
  }

  /**
   * Phase 2 (§15.10, screener): shares outstanding, market cap and industry of one company (Finnhub
   * {@code /stock/profile2}: one call, unlike {@link #fundamentals} which also reads {@code metric}). Empty when the
   * provider knows nothing about the symbol (an ETF, a delisted name).
   */
  default Optional<CompanyProfile> profile(String symbol, RefreshPriority priority) {
    throw unsupported("profile");
  }

  /** At most one upstream stream per provider. */
  default MarketStream openStream(Consumer<MarketEvent> sink) {
    throw unsupported("stream");
  }

  static UnsupportedOperationException unsupported(String what) {
    return new UnsupportedOperationException("provider does not support " + what);
  }
}
