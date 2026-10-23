package com.stocks.marketdata.core;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.Crypto;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.OptionChain;
import com.stocks.contract.marketdata.OptionExpirations;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.OptionQuote;
import com.stocks.marketdata.spi.MarketDataProvider;
import com.stocks.marketdata.spi.ProviderException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Option chains (§30) for the REST and MCP reads: expirations cached 30 minutes per underlying (per ET day), chains
 * cached 10 seconds per expiration, so a page polling every few seconds costs the shared Alpaca budget at most a few
 * calls a minute. Only active stocks and ETFs have options; a crypto pair or an option symbol is
 * {@code SYMBOL_NOT_FOUND}. A provider failure is {@code PROVIDER_UNAVAILABLE} unless a cached answer exists.
 */
public class OptionChainService {

  private static final Logger log = LoggerFactory.getLogger(OptionChainService.class);
  static final Duration EXPIRATIONS_TTL = Duration.ofMinutes(30);
  static final Duration CHAIN_TTL = Duration.ofSeconds(10);
  /** At most this many cached chains (underlying × expiration); the oldest go first. */
  static final int MAX_CHAINS = 200;

  private record Cached<T>(T value, Instant at) {}

  private final MarketDataProvider provider;
  private final AssetService assets;
  private final Function<String, BigDecimal> underlyingPrice;
  private final TimeSource time;
  private final Map<String, Cached<List<LocalDate>>> expirations = new ConcurrentHashMap<>();
  private final Map<String, Cached<OptionChain>> chains = new ConcurrentHashMap<>();

  public OptionChainService(MarketDataProvider provider, AssetService assets, Function<String, BigDecimal> underlyingPrice,
                            TimeSource time) {
    this.provider = provider;
    this.assets = assets;
    this.underlyingPrice = underlyingPrice;
    this.time = time;
  }

  /** {@code indicative}, {@code opra} or {@code simulated}. */
  public String feed() {
    String f = provider.optionFeed();
    return f == null ? "none" : f;
  }

  public OptionExpirations expirations(String underlying) {
    String u = requireOptionable(underlying);
    Instant now = time.now();
    LocalDate today = Et.date(now);
    Cached<List<LocalDate>> c = expirations.get(u);
    if (c == null || Duration.between(c.at(), now).compareTo(EXPIRATIONS_TTL) > 0 || !Et.date(c.at()).equals(today)) {
      try {
        c = new Cached<>(List.copyOf(provider.optionExpirations(u, RefreshPriority.UI)), now);
        expirations.put(u, c);
      } catch (ProviderException | UnsupportedOperationException e) {
        if (c == null) {
          throw unavailable(u, e);
        }
        log.warn("Option expirations for {}: provider failed, serving the cached list: {}", u, e.getMessage());
      }
    }
    List<LocalDate> upcoming = c.value().stream().filter(d -> !d.isBefore(today)).sorted().toList();
    return new OptionExpirations(u, upcoming, feed());
  }

  public OptionChain chain(String underlying, LocalDate expiration) {
    String u = requireOptionable(underlying);
    if (expiration == null) {
      throw ApiException.validation("expiration", "expiration is required (YYYY-MM-DD)");
    }
    Instant now = time.now();
    String key = u + ":" + expiration;
    Cached<OptionChain> c = chains.get(key);
    if (c == null || Duration.between(c.at(), now).compareTo(CHAIN_TTL) > 0) {
      try {
        List<OptionQuote> rows = provider.optionChain(u, expiration, RefreshPriority.UI).stream()
            .filter(q -> q.underlying().equals(u) && q.expiration().equals(expiration))
            .sorted(Comparator.comparing(OptionQuote::strike).thenComparing(OptionQuote::right)).toList();
        c = new Cached<>(new OptionChain(u, expiration, price(u), feed(), now, rows), now);
        if (chains.size() >= MAX_CHAINS) {
          chains.entrySet().stream().min(Comparator.comparing(e -> e.getValue().at()))
              .ifPresent(e -> chains.remove(e.getKey()));
        }
        chains.put(key, c);
      } catch (ProviderException | UnsupportedOperationException e) {
        if (c == null) {
          throw unavailable(u, e);
        }
        log.warn("Option chain {}: provider failed, serving the cached chain: {}", key, e.getMessage());
      }
    }
    return c.value();
  }

  private BigDecimal price(String underlying) {
    try {
      return underlyingPrice.apply(underlying);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The upper-cased underlying when it is an active stock or ETF; {@code SYMBOL_NOT_FOUND} otherwise. */
  String requireOptionable(String underlying) {
    String u = underlying == null ? "" : underlying.trim().toUpperCase(java.util.Locale.ROOT);
    Optional<Asset> a = assets.asset(u);
    if (u.isEmpty() || Crypto.isCrypto(u) || OptionSymbol.isOption(u) || a.isEmpty() || !a.get().active()
        || a.get().type() == AssetType.CRYPTO || a.get().type() == AssetType.OPTION) {
      throw ApiException.of(ErrorCode.SYMBOL_NOT_FOUND, "No listed options for " + (u.isEmpty() ? "''" : u) + ".")
          .withHint("Options are listed for US stocks and ETFs, e.g. SPY, AAPL, NVDA.")
          .withField("underlying", ErrorCode.SYMBOL_NOT_FOUND, "A stock or ETF symbol");
    }
    return u;
  }

  /** Provider details stay in the log (§15.1: raw upstream payloads never reach clients). */
  private static ApiException unavailable(String underlying, RuntimeException e) {
    log.warn("Option data for {} unavailable: {}", underlying, e.toString());
    return ApiException.of(ErrorCode.PROVIDER_UNAVAILABLE, "Option data for " + underlying
            + " is unavailable right now.")
        .withHint("Retry in a minute.");
  }
}
