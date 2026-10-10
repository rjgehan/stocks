package com.stocks.marketdata.alpaca;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.Timeframe;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Snapshot;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.marketdata.core.Json;
import java.time.Instant;
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

  /**
   * Sale conditions that make a print ineligible for fills and triggers (§12.7, §28 #11, conservative): odd lot,
   * average price, out of sequence, derivatively priced, prior reference, cash/next-day/seller, price variation,
   * contingent and bunched-sold trades, and the official open/close summary markers (M, Q, 9), which repeat an
   * auction price instead of being a print. [VERIFY against the CTA/UTP tables on the first live day.]
   */
  static final Set<String> INELIGIBLE = Set.of("I", "W", "B", "Z", "U", "4", "P", "C", "N", "R", "H", "V", "7", "G",
      "M", "Q", "9");

  private AlpacaJson() {}

  /** {@code feed=sip} prints and quotes are consolidated; {@code iex} is a single venue. */
  static boolean consolidatedFeed(String feed) {
    return "sip".equalsIgnoreCase(feed);
  }

  static Trade trade(String symbol, JsonNode n, Instant observedAt, String feed) {
    if (n == null || !Json.present(n, "p")) {
      return null;
    }
    List<String> conditions = Json.strings(n, "c");
    boolean corrected = Json.present(n, "u");
    boolean eligible = !corrected && conditions.stream().noneMatch(INELIGIBLE::contains);
    Instant ts = Json.instant(n, "t");
    return new Trade(symbol, Json.dec(n, "p"), Json.lng(n, "s", 0), ts, observedAt != null ? observedAt : ts,
        Json.str(n, "x"), conditions, consolidatedFeed(feed), eligible, Json.str(n, "i"), SOURCE);
  }

  static Quote quote(String symbol, JsonNode n, Instant observedAt, String feed, long sizeMultiplier) {
    if (n == null || !Json.present(n, "bp") && !Json.present(n, "ap")) {
      return null;
    }
    Instant ts = Json.instant(n, "t");
    DataQuality q = consolidatedFeed(feed) ? DataQuality.REALTIME_CONSOLIDATED : DataQuality.REALTIME_SINGLE_VENUE;
    return new Quote(symbol, Json.dec(n, "bp"), Json.lng(n, "bs", 0) * sizeMultiplier, Json.dec(n, "ap"),
        Json.lng(n, "as", 0) * sizeMultiplier, Json.str(n, "bx"), Json.str(n, "ax"), ts,
        observedAt != null ? observedAt : ts, q, SOURCE);
  }

  static Bar bar(String symbol, Timeframe tf, JsonNode n, boolean partialVolume) {
    if (n == null || !Json.present(n, "o")) {
      return null;
    }
    return new Bar(symbol, tf, Json.instant(n, "t"), Json.dec(n, "o"), Json.dec(n, "h"), Json.dec(n, "l"),
        Json.dec(n, "c"), Json.lng(n, "v", 0), Json.dec(n, "vw"), Json.lng(n, "n", 0), partialVolume);
  }

  static Snapshot snapshot(String symbol, JsonNode n, Instant observedAt, String feed) {
    boolean partial = !consolidatedFeed(feed);
    return new Snapshot(symbol, trade(symbol, n.get("latestTrade"), null, feed),
        quote(symbol, n.get("latestQuote"), observedAt, feed, 1), bar(symbol, Timeframe.MIN1, n.get("minuteBar"), partial),
        bar(symbol, Timeframe.DAY1, n.get("dailyBar"), partial), bar(symbol, Timeframe.DAY1, n.get("prevDailyBar"),
        partial), observedAt);
  }
}
