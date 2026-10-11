package com.stocks.marketdata.core;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.marketdata.Print;
import com.stocks.contract.marketdata.ProviderStatus;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.Tape;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Trade;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The real-time half of market data behind {@code MarketDataServiceImpl}: {@link SimFeed} (the SIM, a pure function
 * of time) or {@link LiveFeed} (Alpaca IEX + Finnhub SIP trades through the {@link QuoteBook}).
 */
public interface MarketFeed extends AutoCloseable {

  /** What {@code MarkService} needs about a symbol. */
  record MarkInputs(Quote quote, Instant quoteConfirmedAt, Trade lastConsolidated, Trade lastTrade,
                    Map<LocalDate, BigDecimal> closes, boolean simulated) {}

  /** {@code LIVE}, {@code DEGRADED} or {@code DOWN} for the market clock (§15.9). */
  enum Health { LIVE, DEGRADED, DOWN }

  void start();

  QuoteView quote(String symbol);

  Map<String, QuoteView> quotes(Collection<String> symbols);

  /** Like {@link #quotes} but never waits on a provider (what is known now). */
  default Map<String, QuoteView> peek(Collection<String> symbols) {
    return quotes(symbols);
  }

  /** §15.10 Time &amp; Sales: the newest {@code limit} stream-delivered prints, newest first. */
  default Tape tape(String symbol, int limit) {
    return Tape.none(symbol);
  }

  /**
   * §15.10 screener warm-up: fetch {@code symbols} that have not been polled recently, in the background at
   * {@code BACKGROUND} priority (never on the caller's thread). A no-op for feeds that are always current (the SIM).
   */
  default void warm(Collection<String> symbols) {
  }

  Optional<ExecQuote> executableQuote(String symbol, Instant notBefore, SessionType session);

  List<Print> drainPrints(String symbol);

  void requestRefresh(String symbol, RefreshPriority priority);

  MarkInputs markInputs(String symbol);

  boolean halted(String symbol);

  /** The forming 1Min bar of a symbol, when known (live tail of {@code BarService}). */
  Optional<Bar> currentMinuteBar(String symbol);

  /** An official close learned from daily marks (live feeds remember it for quotes). */
  default void officialClose(String symbol, LocalDate date, BigDecimal close) {
  }

  List<ProviderStatus> providers();

  /** Leased symbols whose data is stale during venue hours. */
  List<String> staleSymbols(Set<String> leased);

  Health health();

  /** The quality a fresh quote carries ({@code SIMULATED}, {@code REALTIME_SINGLE_VENUE}, …). */
  DataQuality nominalQuality();

  /** {@code 08:00–17:00} for IEX; null when not venue-bound. */
  String liveWindowEt();

  @Override
  void close();
}
