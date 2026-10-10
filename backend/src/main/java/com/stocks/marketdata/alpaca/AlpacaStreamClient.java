package com.stocks.marketdata.alpaca;

import com.stocks.common.domain.Timeframe;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.BarEvent;
import com.stocks.contract.marketdata.model.MarketEvent;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.QuoteEvent;
import com.stocks.contract.marketdata.model.StatusEvent;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.contract.marketdata.model.TradeEvent;
import com.stocks.marketdata.core.Json;
import com.stocks.marketdata.core.WebSocketStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * The Alpaca market-data stream ({@code wss://stream.data.alpaca.markets/v2/iex}, §15.6). Protocol: the server greets
 * with {@code [{"T":"success","msg":"connected"}]}; we send {@code {"action":"auth","key","secret"}} and must see
 * {@code authenticated} within 10 s; subscriptions are diffs ({@code unsubscribe} first, then {@code subscribe}) of
 * {@code trades}, {@code quotes}, {@code bars}, {@code updatedBars} and optionally {@code statuses:["*"]}; the server
 * answers with the full {@code subscription} state. Errors: 406 (connection limit) waits 90 s before reconnecting;
 * 405 (symbol limit) and 409/410 (not in the plan) mark the stream {@code LIMITED}; 402 (auth failed) backs off 5 min.
 * Stream quote sizes are round lots and are normalized to shares ({@link #quoteSizeMultiplier}).
 */
public class AlpacaStreamClient extends WebSocketStream {

  static final Set<String> HALT_CODES = Set.of("H", "2", "P");
  static final Set<String> RESUME_CODES = Set.of("T", "3");

  private final String keyId;
  private final String secret;
  private final String feed;
  private final Consumer<MarketEvent> sink;
  private volatile long quoteSizeMultiplier = 100;
  private volatile boolean statusesWanted;
  private volatile boolean statusesAllowed = true;
  private volatile int symbolLimit = Integer.MAX_VALUE;
  private volatile Integer lastErrorCode;

  private Set<String> reqTrades = new HashSet<>();
  private Set<String> reqQuotes = new HashSet<>();
  private Set<String> reqBars = new HashSet<>();
  private boolean reqStatuses;

  public AlpacaStreamClient(URI uri, String keyId, String secret, String feed, TimeSource time, Timings timings,
                            BooleanSupplier venueHours, Consumer<MarketEvent> sink) {
    super("alpaca", uri, time, timings, venueHours);
    this.keyId = keyId;
    this.secret = secret;
    this.feed = feed;
    this.sink = sink;
  }

  public void setQuoteSizeMultiplier(long m) {
    this.quoteSizeMultiplier = m;
  }

  public long quoteSizeMultiplier() {
    return quoteSizeMultiplier;
  }

  /** Subscribe {@code statuses:["*"]} (halts) once the probe confirms the plan allows it. */
  public void setStatusesWanted(boolean wanted) {
    this.statusesWanted = wanted;
    exec.execute(() -> {
      if (isLive()) {
        sendSubscriptionDiff();
      }
    });
  }

  public boolean statusesActive() {
    return reqStatuses;
  }

  public Integer lastErrorCode() {
    return lastErrorCode;
  }

  /** The symbol limit learned from a 405, if any. */
  public int symbolLimit() {
    return symbolLimit;
  }

  @Override
  protected void onConnected() {
    // wait for the server's "connected" greeting, then authenticate
  }

  @Override
  protected void onDisconnected() {
    super.onDisconnected();
    reqTrades = new HashSet<>();
    reqQuotes = new HashSet<>();
    reqBars = new HashSet<>();
    reqStatuses = false;
  }

  @Override
  protected void onMessage(String text) {
    JsonNode root = Json.parse(text);
    Iterable<JsonNode> items = root.isArray() ? root.values() : List.of(root);
    Instant now = time.now();
    for (JsonNode m : items) {
      String type = Json.str(m, "T");
      if (type == null) {
        continue;
      }
      switch (type) {
        case "success" -> onSuccess(Json.str(m, "msg"));
        case "error" -> onError((int) Json.lng(m, "code", 0), Json.str(m, "msg"));
        case "subscription" -> onSubscription(m, now);
        case "q" -> {
          Quote q = AlpacaJson.quote(Json.str(m, "S"), m, now, feed, quoteSizeMultiplier);
          if (q != null) {
            sink.accept(new QuoteEvent(q));
          }
        }
        case "t" -> {
          Trade t = AlpacaJson.trade(Json.str(m, "S"), m, now, feed);
          if (t != null) {
            sink.accept(new TradeEvent(t));
          }
        }
        case "b", "u" -> {
          Bar b = AlpacaJson.bar(Json.str(m, "S"), Timeframe.MIN1, m, !AlpacaJson.consolidatedFeed(feed));
          if (b != null) {
            sink.accept(new BarEvent(b, type.equals("u")));
          }
        }
        case "s" -> {
          String code = Json.str(m, "sc");
          String sym = Json.str(m, "S");
          Instant ts = Json.instant(m, "t");
          if (HALT_CODES.contains(code)) {
            sink.accept(new StatusEvent(sym, true, Json.str(m, "sm") + " / " + Json.str(m, "rm"), ts));
          } else if (RESUME_CODES.contains(code)) {
            sink.accept(new StatusEvent(sym, false, Json.str(m, "sm"), ts));
          }
        }
        default -> { }
      }
    }
  }

  private void onSuccess(String msg) {
    if ("connected".equals(msg)) {
      Map<String, Object> auth = new LinkedHashMap<>();
      auth.put("action", "auth");
      auth.put("key", keyId);
      auth.put("secret", secret);
      send(Json.write(auth));
    } else if ("authenticated".equals(msg)) {
      lastErrorCode = null;
      markLive();
    }
  }

  private void onError(int code, String msg) {
    lastErrorCode = code;
    switch (code) {
      case 406 -> fail("406 connection limit exceeded (another process uses this key?)",
          timings.afterConnectionLimit());
      case 402 -> fail("402 auth failed (check ALPACA_KEY_ID / ALPACA_SECRET_KEY)", timings.authFailedBackoff());
      case 405 -> {
        symbolLimit = Math.max(1, reqQuotes.size() - 1);
        markLimited("405 symbol limit exceeded at " + reqQuotes.size() + " symbols");
        reqTrades = new HashSet<>(confirmed.keySet());
        reqQuotes = new HashSet<>(confirmed.keySet());
        reqBars = new HashSet<>(confirmed.keySet());
      }
      case 409, 410 -> {
        if (reqStatuses) {
          statusesAllowed = false;
          reqStatuses = false;
          log.info("alpaca stream: statuses not available on this plan ({} {})", code, msg);
        } else {
          markLimited(code + " " + msg);
        }
      }
      case 401, 404 -> fail(code + " " + msg, null);
      default -> log.warn("alpaca stream error {} {}", code, msg);
    }
  }

  private void onSubscription(JsonNode m, Instant now) {
    Set<String> quotes = new TreeSet<>(Json.strings(m, "quotes"));
    confirmed.keySet().removeIf(s -> !quotes.contains(s));
    for (String s : quotes) {
      confirmed.putIfAbsent(s, now);
    }
    reqTrades = new HashSet<>(Json.strings(m, "trades"));
    reqQuotes = new HashSet<>(quotes);
    reqBars = new HashSet<>(Json.strings(m, "bars"));
    reqStatuses = Json.strings(m, "statuses").contains("*");
  }

  @Override
  protected void sendSubscriptionDiff() {
    if (!isLive()) {
      return;
    }
    Set<String> wantT = cap(desiredTrades);
    Set<String> wantQ = cap(desiredQuotes);
    Set<String> wantB = cap(desiredBars);
    Map<String, Object> unsub = new LinkedHashMap<>();
    putDiff(unsub, "trades", reqTrades, wantT);
    putDiff(unsub, "quotes", reqQuotes, wantQ);
    putDiff(unsub, "bars", reqBars, wantB);
    putDiff(unsub, "updatedBars", reqBars, wantB);
    boolean wantStatuses = statusesWanted && statusesAllowed;
    if (reqStatuses && !wantStatuses) {
      unsub.put("statuses", List.of("*"));
    }
    if (!unsub.isEmpty()) {
      unsub.put("action", "unsubscribe");
      send(Json.write(reorder(unsub)));
    }
    Map<String, Object> sub = new LinkedHashMap<>();
    putDiff(sub, "trades", wantT, reqTrades);
    putDiff(sub, "quotes", wantQ, reqQuotes);
    putDiff(sub, "bars", wantB, reqBars);
    putDiff(sub, "updatedBars", wantB, reqBars);
    if (wantStatuses && !reqStatuses) {
      sub.put("statuses", List.of("*"));
    }
    if (!sub.isEmpty()) {
      sub.put("action", "subscribe");
      send(Json.write(reorder(sub)));
    }
    reqTrades = new HashSet<>(wantT);
    reqQuotes = new HashSet<>(wantQ);
    reqBars = new HashSet<>(wantB);
    reqStatuses = wantStatuses;
  }

  private Set<String> cap(Set<String> want) {
    if (want.size() <= symbolLimit) {
      return want;
    }
    return new TreeSet<>(want).stream().limit(symbolLimit).collect(java.util.stream.Collectors.toSet());
  }

  /** {@code a − b} under {@code key} when non-empty. */
  private static void putDiff(Map<String, Object> msg, String key, Set<String> a, Set<String> b) {
    Set<String> d = new TreeSet<>(a);
    d.removeAll(b);
    if (!d.isEmpty()) {
      msg.put(key, List.copyOf(d));
    }
  }

  /** {@code action} first, for readability of logs and fixtures. */
  private static Map<String, Object> reorder(Map<String, Object> m) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("action", m.get("action"));
    m.forEach((k, v) -> {
      if (!k.equals("action")) {
        out.put(k, v);
      }
    });
    return out;
  }

  /** Waits for the scheduler to process everything submitted so far (tests). */
  void flush() throws Exception {
    exec.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
  }

  Duration authTimeout() {
    return timings.authTimeout();
  }
}
