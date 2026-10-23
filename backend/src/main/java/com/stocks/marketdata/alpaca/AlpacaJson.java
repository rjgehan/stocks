package com.stocks.marketdata.alpaca;

import com.stocks.common.domain.Crypto;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.money.Prices;
import com.stocks.common.time.Et;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.OptionQuote;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Snapshot;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.marketdata.core.Json;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Alpaca market-data payloads (§15.6) → model records. Field names follow the official docs: trades
 * {@code t,x,p,s,c,i,z,u}; quotes {@code t,bx,bp,bs,ax,ap,as,c,z}; bars {@code t,o,h,l,c,v,n,vw}; the stream adds
 * {@code T} (type) and {@code S} (symbol). Quote sizes are shares on REST and round lots on the stream
 * ({@code sizeMultiplier}, confirmed by the probe, §15.8).
 */
public final class AlpacaJson {

  public static final String SOURCE = "alpaca";

  /** The {@code feed} label of Alpaca's crypto data (§29): one venue that is the market, treated as consolidated. */
  public static final String CRYPTO_FEED = "crypto";

  /**
   * Sale conditions that make a print ineligible for fills and triggers (§12.7, §28 #11, conservative): odd lot,
   * average price, out of sequence, derivatively priced, prior reference, cash/next-day/seller, price variation,
   * contingent and bunched-sold trades, and the official open/close summary markers (M, Q, 9), which repeat an
   * auction price instead of being a print. [VERIFY against the CTA/UTP tables on the first live day.]
   */
  static final Set<String> INELIGIBLE = Set.of("I", "W", "B", "Z", "U", "4", "P", "C", "N", "R", "H", "V", "7", "G",
      "M", "Q", "9");

  private AlpacaJson() {}

  /** {@code feed=sip} prints and quotes are consolidated; {@code iex} is a single venue; crypto is its own market. */
  static boolean consolidatedFeed(String feed) {
    return "sip".equalsIgnoreCase(feed) || CRYPTO_FEED.equals(feed);
  }

  /**
   * A crypto price on the app's tick (§6.2: $0.01 at or above $1, $0.0001 below): Alpaca's crypto prices carry more
   * decimals than {@code NUMERIC(19,4)} holds. Bids round down and asks up, so the spread never narrows.
   */
  static BigDecimal onTick(BigDecimal price, RoundingMode mode) {
    if (price == null || price.signum() <= 0) {
      return price;
    }
    BigDecimal tick = Prices.tick(price);
    return price.divide(tick, 0, mode).multiply(tick).setScale(Prices.SCALE, RoundingMode.UNNECESSARY);
  }

  /** A crypto size (fractional coins) as a whole count for the size fields: rounded up, so 0.2 BTC shows as 1. */
  static long units(JsonNode n, String field) {
    BigDecimal d = Json.dec(n, field);
    return d == null || d.signum() <= 0 ? 0 : d.setScale(0, RoundingMode.CEILING).longValueExact();
  }

  static Trade trade(String symbol, JsonNode n, Instant observedAt, String feed) {
    if (n == null || !Json.present(n, "p")) {
      return null;
    }
    List<String> conditions = Json.strings(n, "c");
    boolean corrected = Json.present(n, "u");
    boolean eligible = !corrected && conditions.stream().noneMatch(INELIGIBLE::contains);
    Instant ts = Json.instant(n, "t");
    if (crypto(feed)) {
      return new Trade(symbol, onTick(Json.dec(n, "p"), RoundingMode.HALF_UP), units(n, "s"), ts,
          observedAt != null ? observedAt : ts, CRYPTO_FEED, List.of(), true, true, Json.str(n, "i"), SOURCE);
    }
    return new Trade(symbol, Json.dec(n, "p"), Json.lng(n, "s", 0), ts, observedAt != null ? observedAt : ts,
        Json.str(n, "x"), conditions, consolidatedFeed(feed), eligible, Json.str(n, "i"), SOURCE);
  }

  static Quote quote(String symbol, JsonNode n, Instant observedAt, String feed, long sizeMultiplier) {
    if (n == null || !Json.present(n, "bp") && !Json.present(n, "ap")) {
      return null;
    }
    Instant ts = Json.instant(n, "t");
    DataQuality q = consolidatedFeed(feed) ? DataQuality.REALTIME_CONSOLIDATED : DataQuality.REALTIME_SINGLE_VENUE;
    if (crypto(feed)) {
      return new Quote(symbol, onTick(Json.dec(n, "bp"), RoundingMode.DOWN), units(n, "bs"),
          onTick(Json.dec(n, "ap"), RoundingMode.UP), units(n, "as"), CRYPTO_FEED, CRYPTO_FEED, ts,
          observedAt != null ? observedAt : ts, q, SOURCE);
    }
    return new Quote(symbol, Json.dec(n, "bp"), Json.lng(n, "bs", 0) * sizeMultiplier, Json.dec(n, "ap"),
        Json.lng(n, "as", 0) * sizeMultiplier, Json.str(n, "bx"), Json.str(n, "ax"), ts,
        observedAt != null ? observedAt : ts, q, SOURCE);
  }

  static Bar bar(String symbol, Timeframe tf, JsonNode n, boolean partialVolume) {
    if (n == null || !Json.present(n, "o")) {
      return null;
    }
    if (crypto(symbol)) {
      return cryptoBar(symbol, tf, n, Json.instant(n, "t"));
    }
    return new Bar(symbol, tf, Json.instant(n, "t"), Json.dec(n, "o"), Json.dec(n, "h"), Json.dec(n, "l"),
        Json.dec(n, "c"), Json.lng(n, "v", 0), Json.dec(n, "vw"), Json.lng(n, "n", 0), partialVolume);
  }

  /** A crypto bar on the tick; its volume (coins) rounded down to a whole count. */
  static Bar cryptoBar(String symbol, Timeframe tf, JsonNode n, Instant start) {
    BigDecimal v = Json.dec(n, "v");
    BigDecimal vw = Json.dec(n, "vw");
    return new Bar(symbol, tf, start, onTick(Json.dec(n, "o"), RoundingMode.HALF_UP),
        onTick(Json.dec(n, "h"), RoundingMode.HALF_UP), onTick(Json.dec(n, "l"), RoundingMode.HALF_UP),
        onTick(Json.dec(n, "c"), RoundingMode.HALF_UP), v == null ? 0 : v.setScale(0, RoundingMode.FLOOR).longValue(),
        vw == null ? null : onTick(vw, RoundingMode.HALF_UP), Json.lng(n, "n", 0), false);
  }

  /** The crypto feed label, or a crypto pair's symbol. */
  static boolean crypto(String feedOrSymbol) {
    return CRYPTO_FEED.equals(feedOrSymbol) || Crypto.isCrypto(feedOrSymbol);
  }

  /**
   * A crypto snapshot (§29). Alpaca's crypto day bars do not follow the ET calendar, so they are re-dated to the ET
   * days the app uses: the daily bar is today's, the previous one yesterday's (its close is the reference for the
   * day change).
   */
  static Snapshot cryptoSnapshot(String symbol, JsonNode n, Instant observedAt) {
    LocalDate today = Et.date(observedAt);
    JsonNode daily = n.get("dailyBar");
    JsonNode prev = n.get("prevDailyBar");
    return new Snapshot(symbol, trade(symbol, n.get("latestTrade"), null, CRYPTO_FEED),
        quote(symbol, n.get("latestQuote"), observedAt, CRYPTO_FEED, 1),
        bar(symbol, Timeframe.MIN1, n.get("minuteBar"), false),
        daily == null || !Json.present(daily, "o") ? null
            : cryptoBar(symbol, Timeframe.DAY1, daily, Et.startOfDay(today)),
        prev == null || !Json.present(prev, "o") ? null
            : cryptoBar(symbol, Timeframe.DAY1, prev, Et.startOfDay(today.minusDays(1))),
        observedAt);
  }

  // ------------------------------------------------------------------------------------------- options (§30)

  /** The quality of Alpaca's option quotes: {@code opra} is the consolidated feed, {@code indicative} is not. */
  static DataQuality optionQuality(String feed) {
    return "opra".equalsIgnoreCase(feed) ? DataQuality.REALTIME_CONSOLIDATED : DataQuality.INDICATIVE;
  }

  /** An option price on the cent (bids down, asks up, so the spread never narrows); null or 0 when absent. */
  static BigDecimal optionPrice(BigDecimal price, RoundingMode mode) {
    if (price == null || price.signum() <= 0) {
      return price == null ? null : BigDecimal.ZERO.setScale(Prices.SCALE);
    }
    return price.setScale(2, mode).setScale(Prices.SCALE, RoundingMode.UNNECESSARY);
  }

  /** An option quote: cent prices, sizes in contracts, labelled by the option feed. */
  static Quote optionQuote(String symbol, JsonNode n, Instant observedAt, String feed) {
    if (n == null || !Json.present(n, "bp") && !Json.present(n, "ap")) {
      return null;
    }
    Instant ts = Json.instant(n, "t");
    return new Quote(symbol, optionPrice(Json.dec(n, "bp"), RoundingMode.DOWN), Json.lng(n, "bs", 0),
        optionPrice(Json.dec(n, "ap"), RoundingMode.UP), Json.lng(n, "as", 0), Json.str(n, "bx"), Json.str(n, "ax"), ts,
        observedAt != null ? observedAt : ts, optionQuality(feed), SOURCE);
  }

  /**
   * An option print: informative only. It is never consolidated or eligible, so it can neither trigger nor fill an
   * order (the indicative feed's trades are delayed 15 minutes).
   */
  static Trade optionTrade(String symbol, JsonNode n) {
    if (n == null || !Json.present(n, "p")) {
      return null;
    }
    Instant ts = Json.instant(n, "t");
    return new Trade(symbol, Json.dec(n, "p"), Json.lng(n, "s", 0), ts, ts, Json.str(n, "x"), Json.strings(n, "c"),
        false, false, Json.str(n, "i"), SOURCE);
  }

  /** An option snapshot ({@code /v1beta1/options/snapshots}): quote, trade and day bars; volumes are contracts. */
  static Snapshot optionSnapshot(String symbol, JsonNode n, Instant observedAt, String feed) {
    return new Snapshot(symbol, optionTrade(symbol, n.get("latestTrade")),
        optionQuote(symbol, n.get("latestQuote"), observedAt, feed),
        bar(symbol, Timeframe.MIN1, n.get("minuteBar"), false), bar(symbol, Timeframe.DAY1, n.get("dailyBar"), false),
        bar(symbol, Timeframe.DAY1, n.get("prevDailyBar"), false), observedAt);
  }

  /** One chain row; null when the key is not a well-formed OCC symbol. */
  static OptionQuote optionChainRow(String symbol, JsonNode n, Instant observedAt, String feed, Long openInterest) {
    OptionSymbol.Contract c = OptionSymbol.parse(symbol).orElse(null);
    if (c == null) {
      return null;
    }
    JsonNode daily = n.get("dailyBar");
    Long volume = daily != null && Json.present(daily, "v") ? Json.lng(daily, "v", 0) : null;
    JsonNode greeks = n.get("greeks");
    return new OptionQuote(c.symbol(), c.underlying(), c.expiration(), c.right(), c.strike(),
        optionQuote(symbol, n.get("latestQuote"), observedAt, feed), optionTrade(symbol, n.get("latestTrade")), volume,
        openInterest, Json.dec(n, "impliedVolatility"), greeks == null ? null : Json.dec(greeks, "delta"));
  }

  /** As {@link #optionChainRow(String, JsonNode, Instant, String, Long)} without open interest. */
  static OptionQuote optionChainRow(String symbol, JsonNode n, Instant observedAt, String feed) {
    return optionChainRow(symbol, n, observedAt, feed, null);
  }

  static Snapshot snapshot(String symbol, JsonNode n, Instant observedAt, String feed) {
    boolean partial = !consolidatedFeed(feed);
    return new Snapshot(symbol, trade(symbol, n.get("latestTrade"), null, feed),
        quote(symbol, n.get("latestQuote"), observedAt, feed, 1), bar(symbol, Timeframe.MIN1, n.get("minuteBar"), partial),
        bar(symbol, Timeframe.DAY1, n.get("dailyBar"), partial), bar(symbol, Timeframe.DAY1, n.get("prevDailyBar"),
        partial), observedAt);
  }
}
