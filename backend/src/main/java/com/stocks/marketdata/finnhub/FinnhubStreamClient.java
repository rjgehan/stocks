package com.stocks.marketdata.finnhub;

import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.model.MarketEvent;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.contract.marketdata.model.TradeEvent;
import com.stocks.marketdata.core.Json;
import com.stocks.marketdata.core.WebSocketStream;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * The Finnhub US trade stream ({@code wss://ws.finnhub.io?token=…}, §15.6): consolidated (SIP) last sales used for the
 * consolidated last, stop triggers and the IEX-dark windows. One message per symbol:
 * {@code {"type":"subscribe","symbol":"AAPL"}}; data arrives as {@code {"type":"trade","data":[{s,p,t(ms),v,c[]}]}};
 * {@code ping} messages are ignored. Finnhub does not confirm subscriptions, so a symbol counts as subscribed from the
 * moment the request is sent.
 */
public class FinnhubStreamClient extends WebSocketStream {

  /**
   * Finnhub trade-condition codes treated as ineligible. Finnhub sends numeric codes whose CTA/UTP mapping is not
   * verified yet (§28 #11), and a wrong entry would silently drop regular or extended-hours prints, so the set starts
   * empty: every Finnhub print is eligible until the mapping is confirmed against a live capture. [VERIFY]
   */
  static final Set<String> INELIGIBLE = Set.of();

  private final Consumer<MarketEvent> sink;
  private Set<String> requested = new TreeSet<>();

  public FinnhubStreamClient(URI uri, TimeSource time, Timings timings, BooleanSupplier venueHours,
                             Consumer<MarketEvent> sink) {
    super("finnhub", uri, time, timings, venueHours);
    this.sink = sink;
  }

  @Override
  protected void onConnected() {
    markLive();                                     // no auth message: the token is in the URL
  }

  @Override
  protected void onDisconnected() {
    super.onDisconnected();
    requested = new TreeSet<>();
  }

  @Override
  protected void onMessage(String text) {
    JsonNode root = Json.parse(text);
    String type = Json.str(root, "type");
    if ("trade".equals(type) && root.get("data") != null) {
      Instant now = time.now();
      for (JsonNode d : root.get("data").values()) {
        String sym = Json.str(d, "s");
        if (sym == null || !Json.present(d, "p")) {
          continue;
        }
        List<String> conditions = Json.strings(d, "c");
        Instant ts = Instant.ofEpochMilli(Json.lng(d, "t", now.toEpochMilli())).truncatedTo(ChronoUnit.MICROS);
        boolean eligible = conditions.stream().noneMatch(INELIGIBLE::contains);
        sink.accept(new TradeEvent(new Trade(sym, Json.dec(d, "p"), Json.lng(d, "v", 0), ts, now, null, conditions,
            true, eligible, null, FinnhubRestClient.ID)));
      }
    } else if ("error".equals(type)) {
      log.warn("finnhub stream error: {}", Json.str(root, "msg"));
    }
  }

  @Override
  protected void sendSubscriptionDiff() {
    if (!isLive()) {
      return;
    }
    Set<String> want = new TreeSet<>(desiredTrades);
    Instant now = time.now();
    for (String s : new TreeSet<>(requested)) {
      if (!want.contains(s)) {
        send(message("unsubscribe", s));
        requested.remove(s);
        confirmed.remove(s);
      }
    }
    for (String s : want) {
      if (!requested.contains(s)) {
        send(message("subscribe", s));
        requested.add(s);
        confirmed.put(s, now);
      }
    }
  }

  private static String message(String type, String symbol) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    m.put("symbol", symbol);
    return Json.write(m);
  }
}
