package com.stocks.realtime;

import com.stocks.contract.marketdata.LeasePriority;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The §19 destinations a client may SUBSCRIBE to, parsed. Anything else is refused by {@link StompAuthInterceptor}.
 *
 * <table>
 *   <caption>Destinations</caption>
 *   <tr><th>Destination</th><th>Kind</th><th>Lease</th></tr>
 *   <tr><td>{@code /topic/quote.{SYM}}</td><td>{@link Kind#QUOTE}</td><td>VIEWING</td></tr>
 *   <tr><td>{@code /topic/tick.{SYM}}</td><td>{@link Kind#TICK}</td><td>LIST</td></tr>
 *   <tr><td>{@code /topic/bars.{SYM}.1Min}</td><td>{@link Kind#BARS}</td><td>VIEWING</td></tr>
 *   <tr><td>{@code /topic/market.session}, {@code /topic/leaderboard}, {@code /topic/feed}</td><td>broadcast</td>
 *       <td>none</td></tr>
 *   <tr><td>{@code /user/queue/orders|account|notifications}</td><td>the caller's own queue</td><td>none</td></tr>
 *   <tr><td>{@code /user/queue/admin}</td><td>{@link Kind#ADMIN_QUEUE} (ROLE_ADMIN only)</td><td>none</td></tr>
 * </table>
 *
 * Symbols are canonical upper-case tickers; one that is not 1–12 characters of {@code [A-Z0-9.-]} starting with a
 * letter is {@link #invalidSymbol} (silently ignored, never an error frame, so a bad watchlist entry cannot kill the
 * socket).
 */
public final class StompDestinations {

  /** What a destination is. The three market kinds count toward the per-session cap of §17.5. */
  public enum Kind {
    QUOTE, TICK, BARS, SESSION, LEADERBOARD, FEED, USER_QUEUE, ADMIN_QUEUE;

    /** Market-data kinds: counted toward the 60-per-session cap and backed by a lease. */
    public boolean isMarket() {
      return this == QUOTE || this == TICK || this == BARS;
    }

    /** The lease priority a subscription of this kind holds (§15.5), or null. */
    public LeasePriority leasePriority() {
      return switch (this) {
        case QUOTE, BARS -> LeasePriority.VIEWING;
        case TICK -> LeasePriority.LIST;
        default -> null;
      };
    }
  }

  /** A parsed destination; {@code symbol} is set for the market kinds. */
  public record Parsed(Kind kind, String symbol, String destination) {}

  public static final String QUOTE_PREFIX = "/topic/quote.";
  public static final String TICK_PREFIX = "/topic/tick.";
  public static final String BARS_PREFIX = "/topic/bars.";
  public static final String BARS_SUFFIX = ".1Min";
  public static final String SESSION_TOPIC = "/topic/market.session";
  public static final String LEADERBOARD_TOPIC = "/topic/leaderboard";
  public static final String FEED_TOPIC = "/topic/feed";

  /** User queues, as the client subscribes to them ({@code /user} + queue) and as the server sends them. */
  public static final String ORDERS_QUEUE = "/queue/orders";
  public static final String ACCOUNT_QUEUE = "/queue/account";
  public static final String NOTIFICATIONS_QUEUE = "/queue/notifications";
  public static final String ADMIN_QUEUE = "/queue/admin";
  public static final String USER_PREFIX = "/user";

  private static final Pattern SYMBOL = Pattern.compile("^[A-Z][A-Z0-9.\\-]{0,11}$");
  private static final Pattern BARS = Pattern.compile("^/topic/bars\\.(.+)\\.1Min$");

  private StompDestinations() {}

  /** Parses a SUBSCRIBE destination; empty when it is not a §19 destination at all. */
  public static Optional<Parsed> parse(String destination) {
    if (destination == null) {
      return Optional.empty();
    }
    switch (destination) {
      case SESSION_TOPIC -> {
        return Optional.of(new Parsed(Kind.SESSION, null, destination));
      }
      case LEADERBOARD_TOPIC -> {
        return Optional.of(new Parsed(Kind.LEADERBOARD, null, destination));
      }
      case FEED_TOPIC -> {
        return Optional.of(new Parsed(Kind.FEED, null, destination));
      }
      case USER_PREFIX + ORDERS_QUEUE, USER_PREFIX + ACCOUNT_QUEUE, USER_PREFIX + NOTIFICATIONS_QUEUE -> {
        return Optional.of(new Parsed(Kind.USER_QUEUE, null, destination));
      }
      case USER_PREFIX + ADMIN_QUEUE -> {
        return Optional.of(new Parsed(Kind.ADMIN_QUEUE, null, destination));
      }
      default -> {
        // market topics below
      }
    }
    Matcher bars = BARS.matcher(destination);
    if (destination.startsWith(BARS_PREFIX) && bars.matches()) {
      return Optional.of(new Parsed(Kind.BARS, symbolOrInvalid(bars.group(1)), destination));
    }
    if (destination.startsWith(QUOTE_PREFIX)) {
      return Optional.of(new Parsed(Kind.QUOTE, symbolOrInvalid(destination.substring(QUOTE_PREFIX.length())),
          destination));
    }
    if (destination.startsWith(TICK_PREFIX)) {
      return Optional.of(new Parsed(Kind.TICK, symbolOrInvalid(destination.substring(TICK_PREFIX.length())),
          destination));
    }
    return Optional.empty();
  }

  /**
   * The symbol, or {@code null} when it is not a canonical (upper-case) ticker. Destinations match exactly in the
   * broker, so {@code /topic/quote.aapl} would never receive anything: it is refused rather than silently dead.
   */
  static String symbolOrInvalid(String raw) {
    return raw != null && SYMBOL.matcher(raw).matches() ? raw : null;
  }

  /** True for a parsed market destination whose symbol did not validate. */
  public static boolean invalidSymbol(Parsed p) {
    return p.kind().isMarket() && p.symbol() == null;
  }

  /** {@code /topic/quote.AAPL}. */
  public static String quote(String symbol) {
    return QUOTE_PREFIX + symbol;
  }

  /** {@code /topic/tick.AAPL}. */
  public static String tick(String symbol) {
    return TICK_PREFIX + symbol;
  }

  /** {@code /topic/bars.AAPL.1Min}. */
  public static String bars(String symbol) {
    return BARS_PREFIX + symbol + BARS_SUFFIX;
  }
}
