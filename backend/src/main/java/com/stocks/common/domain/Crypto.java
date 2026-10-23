package com.stocks.common.domain;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Crypto pairs (§29): the league's seeded list of USD pairs that trade 24/7, and the symbol rules every layer shares.
 * A crypto symbol is {@code BASE/QUOTE} with a slash ({@code BTC/USD}, Alpaca's spelling); no stock symbol has one, so
 * {@link #isCrypto} is a pure, lookup-free test that the engine, posting, fees and market data all use. URL paths
 * cannot carry the slash ({@code %2F} is refused by the servlet firewall), so paths spell a pair with a dash
 * ({@code BTC-USD}); {@link #fromPath} maps it back for the seeded pairs only, so a stock symbol is never rewritten.
 */
public final class Crypto {

  /** One supported pair: the display name, the SIM reference price (§15.7) and the liquidity tier. */
  public record Pair(String symbol, String name, BigDecimal refPrice, LiquidityTier tier) {
    public String base() {
      return symbol.substring(0, symbol.indexOf('/'));
    }
  }

  /** The seeded pairs, in display order (Alpaca crypto, USD quote, priced at $0.01 or more). */
  public static final List<Pair> PAIRS = List.of(
      pair("BTC/USD", "Bitcoin", "112000.00", LiquidityTier.MEGA),
      pair("ETH/USD", "Ethereum", "4200.00", LiquidityTier.MEGA),
      pair("SOL/USD", "Solana", "210.00", LiquidityTier.MID),
      pair("XRP/USD", "XRP", "2.60", LiquidityTier.MID),
      pair("DOGE/USD", "Dogecoin", "0.24", LiquidityTier.MID),
      pair("LTC/USD", "Litecoin", "105.00", LiquidityTier.MID),
      pair("BCH/USD", "Bitcoin Cash", "540.00", LiquidityTier.MID),
      pair("LINK/USD", "Chainlink", "22.00", LiquidityTier.MID),
      pair("AVAX/USD", "Avalanche", "30.00", LiquidityTier.SMALL),
      pair("DOT/USD", "Polkadot", "4.20", LiquidityTier.SMALL),
      pair("UNI/USD", "Uniswap", "8.50", LiquidityTier.SMALL),
      pair("AAVE/USD", "Aave", "280.00", LiquidityTier.SMALL));

  /** The largest crypto order quantity (stocks: {@code stocks.rules.max-order-qty}, 200,000 shares). */
  public static final BigDecimal MAX_ORDER_QTY = new BigDecimal("100000000");

  /**
   * Every symbol the app accepts at its boundaries: a stock or ETF ticker ({@code AAPL}, {@code BRK.B}), a crypto
   * pair ({@code BTC/USD}) or an OCC option contract ({@code AAPL261120C00250000}, §30). Apply {@link #fromPath}
   * first, so {@code BTC-USD} is accepted as {@code BTC/USD}.
   */
  public static final String SYMBOL_REGEX = "^[A-Z][A-Z0-9.\\-]{0,11}$|^[A-Z0-9]{2,10}/[A-Z]{3,4}$|"
      + "^[A-Z]{1,6}[0-9]{6}[CP][0-9]{8}$";

  /** The exchange label on crypto assets. */
  public static final String EXCHANGE = "CRYPTO";

  private static final Map<String, Pair> BY_SYMBOL = index();

  private Crypto() {}

  private static Pair pair(String symbol, String name, String ref, LiquidityTier tier) {
    return new Pair(symbol, name, new BigDecimal(ref), tier);
  }

  private static Map<String, Pair> index() {
    Map<String, Pair> m = new LinkedHashMap<>();
    for (Pair p : PAIRS) {
      m.put(p.symbol(), p);
    }
    return Map.copyOf(m);
  }

  /** A crypto symbol: it has a slash ({@code BTC/USD}). Null-safe. */
  public static boolean isCrypto(String symbol) {
    return symbol != null && symbol.indexOf('/') > 0;
  }

  /** The seeded pair, if {@code symbol} is one. */
  public static Optional<Pair> pair(String symbol) {
    return symbol == null ? Optional.empty() : Optional.ofNullable(BY_SYMBOL.get(symbol.toUpperCase(Locale.ROOT)));
  }

  public static List<String> symbols() {
    return PAIRS.stream().map(Pair::symbol).toList();
  }

  /**
   * A symbol from a URL path segment or a loose client spelling: upper-cased, and {@code BTC-USD} or {@code BTCUSD}
   * becomes {@code BTC/USD} when that is a seeded pair. Anything else is returned upper-cased and unchanged.
   */
  public static String fromPath(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.trim().toUpperCase(Locale.ROOT);
    if (s.indexOf('/') >= 0) {
      return s;
    }
    String dashed = s.replace('-', '/');
    if (BY_SYMBOL.containsKey(dashed)) {
      return dashed;
    }
    for (Pair p : PAIRS) {
      if (p.symbol().replace("/", "").equals(s)) {
        return p.symbol();
      }
    }
    return s;
  }

  /** The URL path spelling of a symbol: {@code BTC/USD} → {@code BTC-USD}; stocks unchanged. */
  public static String toPath(String symbol) {
    return symbol == null ? null : symbol.replace('/', '-');
  }
}
