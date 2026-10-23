package com.stocks.contract.marketdata;

import com.stocks.common.domain.MoverList;
import com.stocks.common.domain.SessionType;
import com.stocks.common.error.ApiException;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.contract.marketdata.model.Movers;
import com.stocks.contract.marketdata.model.NewsItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single market-data facade (§5.3, §15, WS2). The Day-0 default is {@code config.stubs.NoopMarketDataService};
 * tests use the test kit's {@code ScriptedMarketData}.
 */
public interface MarketDataService {

  Optional<Asset> asset(String symbol);

  List<Asset> search(String query, int limit);

  /** Never null; quality {@code NONE} when nothing is known. */
  QuoteView quote(String symbol);

  Map<String, QuoteView> quotes(Collection<String> symbols);

  /** §12.3: a quote confirmed at or after {@code notBefore}, usable in {@code session}. */
  Optional<ExecQuote> executableQuote(String symbol, Instant notBefore, SessionType session);

  /** ENGINE ONLY: eligible prints since the last drain. */
  List<Print> drainPrints(String symbol);

  /** Async; completes with {@link MarketUpdateListener} callbacks. */
  void requestRefresh(String symbol, RefreshPriority priority);

  /** Live mark, §15.5 (split-adjusted). */
  Mark mark(String symbol);

  /** Official prior close, split-adjusted to today. */
  Optional<BigDecimal> previousClose(String symbol);

  /** RAW day-end mark as of {@code tradeDate} (EOD valuation of that day). */
  Optional<BigDecimal> dayEndMark(String symbol, LocalDate tradeDate);

  BarSeries bars(BarRequest request);

  Movers movers(MoverList list, int limit);

  List<NewsItem> news(Set<String> symbols, int limit, Instant before);

  Optional<Fundamentals> fundamentals(String symbol);

  /** Idempotent per {@code (symbol, ownerKey)}. */
  LeaseHandle lease(String symbol, LeasePriority priority, String ownerKey);

  /** Drops every lease held by {@code ownerKey}. */
  void releaseOwner(String ownerKey);

  /** True while halted OR under a corporate-action hold (§14). */
  boolean isHalted(String symbol);

  /**
   * True while the symbol is under a corporate-action hold (§14: a split waiting for the admin). Asset DTOs then
   * report {@code tradable = false}, since new orders are rejected until the admin resolves it.
   */
  default boolean onCorporateActionHold(String symbol) {
    return false;
  }

  MarketDataStatus status();

  /**
   * Phase 2 (§15.10): the symbol's Time &amp; Sales tape, newest first, at most {@code limit} prints. Only prints a
   * trade stream delivered (never sampled or synthesized ones); {@link Tape#none} when no stream covers the symbol.
   */
  default Tape timeAndSales(String symbol, int limit) {
    return Tape.none(symbol);
  }

  /**
   * Phase 2 (§15.10): analyst recommendation trends (Finnhub free {@code /stock/recommendation}, cached); empty when
   * no source has them (no Finnhub token, an ETF, a provider failure).
   */
  default Optional<Recommendations> recommendations(String symbol) {
    return Optional.empty();
  }

  /**
   * §30: the listed expirations of {@code underlying} (a stock or ETF). {@code SYMBOL_NOT_FOUND} for an unknown or
   * non-optionable symbol, {@code PROVIDER_UNAVAILABLE} when the provider fails.
   */
  default OptionExpirations optionExpirations(String underlying) {
    throw ApiException.notImplemented("options");
  }

  /** §30: one expiration's chain of {@code underlying}, with quotes; errors as {@link #optionExpirations}. */
  default OptionChain optionChain(String underlying, LocalDate expiration) {
    throw ApiException.notImplemented("options");
  }

  /**
   * Phase 2 (§15.10): screens the universe with the latest quotes and the {@code screener_stat} statistics; never
   * calls a provider on the caller's thread. {@code NOT_IMPLEMENTED} where no market data runtime exists.
   */
  default ScreenResult screen(ScreenQuery query) {
    throw ApiException.notImplemented("screener");
  }
}
