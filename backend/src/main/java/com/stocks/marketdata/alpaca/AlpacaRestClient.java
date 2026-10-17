package com.stocks.marketdata.alpaca;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.Timeframe;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.CorporateActionData;
import com.stocks.contract.marketdata.model.Mover;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.contract.marketdata.model.Quote;
import com.stocks.contract.marketdata.model.Snapshot;
import com.stocks.contract.marketdata.model.Trade;
import com.stocks.contract.marketdata.model.TradingDayData;
import com.stocks.marketdata.config.MarketDataProperties;
import com.stocks.marketdata.core.Json;
import com.stocks.marketdata.core.ProviderHttp;
import com.stocks.marketdata.spi.BarQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * Alpaca REST (§15.6): market data on {@code data-url} ({@code /v2/stocks/*}, {@code /v1beta1/*}; always with an
 * explicit {@code feed}) and the trading API on {@code trading-url} only for {@code /v2/assets}, {@code /v2/calendar}
 * and {@code /v2/clock}. Every call goes through the shared 180/min {@link ProviderHttp} budget.
 */
public class AlpacaRestClient {

  public static final String ID = "alpaca";
  static final int MAX_BAR_PAGE = 10_000;

  private final MarketDataProperties.Alpaca props;
  private final ProviderHttp http;
  private final Supplier<Instant> clock;

  public AlpacaRestClient(MarketDataProperties.Alpaca props, ProviderHttp http, Supplier<Instant> clock) {
    this.props = props;
    this.http = http;
    this.clock = clock;
  }

  public ProviderHttp http() {
    return http;
  }

  // ------------------------------------------------------------------------------------------- quotes and trades

  /** {@code GET /v2/stocks/snapshots?symbols=…&feed=…} (top-level object keyed by symbol). */
  public Map<String, Snapshot> snapshots(Set<String> symbols, String feed, RefreshPriority priority) {
    Map<String, Snapshot> out = new LinkedHashMap<>();
    if (symbols.isEmpty()) {
      return out;
    }
    JsonNode root = http.getJson(priority, props.dataUrl(), "/v2/stocks/snapshots",
        ProviderHttp.query("symbols", String.join(",", new TreeSet<>(symbols)), "feed", feed));
    Instant observed = clock.get();
    JsonNode body = root.has("snapshots") && root.get("snapshots").isObject() ? root.get("snapshots") : root;
    for (Map.Entry<String, JsonNode> e : body.properties()) {
      if (e.getValue() != null && e.getValue().isObject()) {
        out.put(e.getKey(), AlpacaJson.snapshot(e.getKey(), e.getValue(), observed, feed));
      }
    }
    return out;
  }

  /** {@code GET /v2/stocks/quotes/latest} → {@code {quotes: {SYM: quote}}} (sizes in shares). */
  public Map<String, Quote> latestQuotes(Set<String> symbols, String feed, RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.dataUrl(), "/v2/stocks/quotes/latest",
        ProviderHttp.query("symbols", String.join(",", new TreeSet<>(symbols)), "feed", feed));
    Instant observed = clock.get();
    Map<String, Quote> out = new LinkedHashMap<>();
    JsonNode quotes = root.get("quotes");
    if (quotes != null && quotes.isObject()) {
      for (Map.Entry<String, JsonNode> e : quotes.properties()) {
        Quote q = AlpacaJson.quote(e.getKey(), e.getValue(), observed, feed, 1);
        if (q != null) {
          out.put(e.getKey(), q);
        }
      }
    }
    return out;
  }

  /** {@code GET /v2/stocks/trades/latest} → {@code {trades: {SYM: trade}}}. */
  public Map<String, Trade> latestTrades(Set<String> symbols, String feed, RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.dataUrl(), "/v2/stocks/trades/latest",
        ProviderHttp.query("symbols", String.join(",", new TreeSet<>(symbols)), "feed", feed));
    Map<String, Trade> out = new LinkedHashMap<>();
    JsonNode trades = root.get("trades");
    if (trades != null && trades.isObject()) {
      for (Map.Entry<String, JsonNode> e : trades.properties()) {
        Trade t = AlpacaJson.trade(e.getKey(), e.getValue(), null, feed);
        if (t != null) {
          out.put(e.getKey(), t);
        }
      }
    }
    return out;
  }

  // ------------------------------------------------------------------------------------------- bars

  /**
   * {@code GET /v2/stocks/bars} with {@code timeframe}, {@code start}, {@code end}, {@code adjustment}, {@code feed},
   * {@code sort=asc}; follows {@code next_page_token} until {@code q.limit()} bars in total.
   */
  public Map<String, List<Bar>> bars(BarQuery q, RefreshPriority priority) {
    Map<String, List<Bar>> out = new LinkedHashMap<>();
    if (q.symbols().isEmpty()) {
      return out;
    }
    String token = null;
    int total = 0;
    int limit = q.limit() <= 0 ? MAX_BAR_PAGE : q.limit();
    boolean partial = !"sip".equalsIgnoreCase(q.feed());
    // Alpaca applies `limit` on the server in the requested sort order. A single-symbol query (every BarService call)
    // wants the newest `limit` bars that end at `end`, as the SPI and SIM promise, so it asks newest first and
    // reverses; multi-symbol batch jobs fetch whole windows and keep ascending order.
    boolean newestFirst = q.symbols().size() == 1;
    for (int page = 0; page < 50; page++) {
      JsonNode root = http.getJson(priority, props.dataUrl(), "/v2/stocks/bars", ProviderHttp.query(
          "symbols", String.join(",", new TreeSet<>(q.symbols())), "timeframe", q.tf().wireValue(),
          "start", q.from() == null ? null : DateTimeFormatter.ISO_INSTANT.format(q.from()),
          "end", q.to() == null ? null : DateTimeFormatter.ISO_INSTANT.format(q.to()),
          "limit", Math.min(MAX_BAR_PAGE, limit - total), "adjustment", adjustment(q),
          "feed", q.feed(), "sort", newestFirst ? "desc" : "asc", "page_token", token));
      JsonNode bars = root.get("bars");
      if (bars != null && bars.isObject()) {
        for (Map.Entry<String, JsonNode> e : bars.properties()) {
          List<Bar> list = out.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
          for (JsonNode b : e.getValue().values()) {
            Bar bar = AlpacaJson.bar(e.getKey(), q.tf(), b, partial);
            if (bar != null) {
              list.add(bar);
              total++;
            }
          }
        }
      }
      token = Json.str(root, "next_page_token");
      if (token == null || token.isBlank() || total >= limit) {
        break;
      }
    }
    if (newestFirst) {
      out.values().forEach(java.util.Collections::reverse);   // callers always get ascending bars
    }
    return out;
  }

  static String adjustment(BarQuery q) {
    return switch (q.adj()) {
      case RAW -> "raw";
      case SPLIT -> "split";
      case ALL -> "all";
    };
  }

  // ------------------------------------------------------------------------------------------- crypto (§29)

  /** Alpaca's US crypto market data: no {@code feed} and no SIP embargo; symbols are pairs ({@code BTC/USD}). */
  static final String CRYPTO = "/v1beta3/crypto/us";

  /** {@code GET /v1beta3/crypto/us/snapshots?symbols=…} → {@code {snapshots: {PAIR: snapshot}}}. */
  public Map<String, Snapshot> cryptoSnapshots(Set<String> symbols, RefreshPriority priority) {
    Map<String, Snapshot> out = new LinkedHashMap<>();
    if (symbols.isEmpty()) {
      return out;
    }
    JsonNode root = http.getJson(priority, props.dataUrl(), CRYPTO + "/snapshots",
        ProviderHttp.query("symbols", String.join(",", new TreeSet<>(symbols))));
    Instant observed = clock.get();
    JsonNode body = root.has("snapshots") && root.get("snapshots").isObject() ? root.get("snapshots") : root;
    for (Map.Entry<String, JsonNode> e : body.properties()) {
      if (e.getValue() != null && e.getValue().isObject()) {
        out.put(e.getKey(), AlpacaJson.cryptoSnapshot(e.getKey(), e.getValue(), observed));
      }
    }
    return out;
  }

  /** {@code GET /v1beta3/crypto/us/latest/quotes} → {@code {quotes: {PAIR: quote}}}. */
  public Map<String, Quote> cryptoLatestQuotes(Set<String> symbols, RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.dataUrl(), CRYPTO + "/latest/quotes",
        ProviderHttp.query("symbols", String.join(",", new TreeSet<>(symbols))));
    Instant observed = clock.get();
    Map<String, Quote> out = new LinkedHashMap<>();
    JsonNode quotes = root.get("quotes");
    if (quotes != null && quotes.isObject()) {
      for (Map.Entry<String, JsonNode> e : quotes.properties()) {
        Quote q = AlpacaJson.quote(e.getKey(), e.getValue(), observed, AlpacaJson.CRYPTO_FEED, 1);
        if (q != null) {
          out.put(e.getKey(), q);
        }
      }
    }
    return out;
  }

  /** {@code GET /v1beta3/crypto/us/latest/trades} → {@code {trades: {PAIR: trade}}}. */
  public Map<String, Trade> cryptoLatestTrades(Set<String> symbols, RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.dataUrl(), CRYPTO + "/latest/trades",
        ProviderHttp.query("symbols", String.join(",", new TreeSet<>(symbols))));
    Map<String, Trade> out = new LinkedHashMap<>();
    JsonNode trades = root.get("trades");
    if (trades != null && trades.isObject()) {
      for (Map.Entry<String, JsonNode> e : trades.properties()) {
        Trade t = AlpacaJson.trade(e.getKey(), e.getValue(), null, AlpacaJson.CRYPTO_FEED);
        if (t != null) {
          out.put(e.getKey(), t);
        }
      }
    }
    return out;
  }

  /**
   * {@code GET /v1beta3/crypto/us/bars} with {@code timeframe}, {@code start}, {@code end}, {@code limit} and
   * {@code sort}, following {@code next_page_token} like {@link #bars}. Crypto has no adjustments and no feed.
   */
  public Map<String, List<Bar>> cryptoBars(BarQuery q, RefreshPriority priority) {
    Map<String, List<Bar>> out = new LinkedHashMap<>();
    if (q.symbols().isEmpty()) {
      return out;
    }
    String token = null;
    int total = 0;
    int limit = q.limit() <= 0 ? MAX_BAR_PAGE : q.limit();
    boolean newestFirst = q.symbols().size() == 1;
    for (int page = 0; page < 50; page++) {
      JsonNode root = http.getJson(priority, props.dataUrl(), CRYPTO + "/bars", ProviderHttp.query(
          "symbols", String.join(",", new TreeSet<>(q.symbols())), "timeframe", q.tf().wireValue(),
          "start", q.from() == null ? null : DateTimeFormatter.ISO_INSTANT.format(q.from()),
          "end", q.to() == null ? null : DateTimeFormatter.ISO_INSTANT.format(q.to()),
          "limit", Math.min(MAX_BAR_PAGE, limit - total), "sort", newestFirst ? "desc" : "asc", "page_token", token));
      JsonNode bars = root.get("bars");
      if (bars != null && bars.isObject()) {
        for (Map.Entry<String, JsonNode> e : bars.properties()) {
          List<Bar> list = out.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
          for (JsonNode b : e.getValue().values()) {
            Bar bar = AlpacaJson.bar(e.getKey(), q.tf(), b, false);
            if (bar != null) {
              list.add(bar);
              total++;
            }
          }
        }
      }
      token = Json.str(root, "next_page_token");
      if (token == null || token.isBlank() || total >= limit) {
        break;
      }
    }
    if (newestFirst) {
      out.values().forEach(java.util.Collections::reverse);
    }
    return out;
  }

  // ------------------------------------------------------------------------------------------- trading API

  /** {@code GET /v2/assets?status=active&asset_class=us_equity} (trading URL). */
  public List<Asset> assets(RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.tradingUrl(), "/v2/assets",
        ProviderHttp.query("status", "active", "asset_class", "us_equity"));
    List<Asset> out = new ArrayList<>();
    for (JsonNode a : root.values()) {
      String symbol = Json.str(a, "symbol");
      if (symbol == null) {
        continue;
      }
      boolean easyToBorrow = Json.bool(a, "easy_to_borrow", "easy_to_borrow".equals(Json.str(a, "borrow_status")));
      out.add(new Asset(symbol.toUpperCase(Locale.ROOT), Json.str(a, "name") == null ? symbol : Json.str(a, "name"),
          Json.str(a, "exchange"), AssetType.UNKNOWN, Json.bool(a, "tradable", false),
          Json.bool(a, "fractionable", false), Json.bool(a, "shortable", false), easyToBorrow,
          Json.bool(a, "marginable", false), LiquidityTier.SMALL, "active".equalsIgnoreCase(Json.str(a, "status"))));
    }
    return out;
  }

  /**
   * {@code GET /v2/calendar?start&end} (trading URL): {@code date}, {@code open}/{@code close} ({@code HH:mm}),
   * {@code session_open}/{@code session_close} ({@code HHmm}) and {@code settlement_date}.
   */
  public List<TradingDayData> calendar(LocalDate from, LocalDate to, RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.tradingUrl(), "/v2/calendar",
        ProviderHttp.query("start", from.toString(), "end", to.toString()));
    List<TradingDayData> out = new ArrayList<>();
    for (JsonNode d : root.values()) {
      LocalDate date = Json.date(d, "date");
      LocalTime open = time(Json.str(d, "open"));
      LocalTime close = time(Json.str(d, "close"));
      if (date == null || open == null || close == null) {
        continue;
      }
      LocalTime pre = time(Json.str(d, "session_open"));
      LocalTime post = time(Json.str(d, "session_close"));
      out.add(new TradingDayData(date, pre, open, close, post, Json.date(d, "settlement_date")));
    }
    return out;
  }

  /** {@code 09:30}, {@code 0930} or {@code 09:30:00}. */
  static LocalTime time(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    String v = s.trim();
    try {
      if (v.matches("\\d{4}")) {
        return LocalTime.of(Integer.parseInt(v.substring(0, 2)), Integer.parseInt(v.substring(2)));
      }
      return LocalTime.parse(v.length() == 5 ? v : v.substring(0, Math.min(8, v.length())));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** {@code GET /v2/clock} (sanity check): {@code is_open}, {@code timestamp}. */
  public JsonNode clock(RefreshPriority priority) {
    return http.getJson(priority, props.tradingUrl(), "/v2/clock", Map.of());
  }

  // ------------------------------------------------------------------------------------------- news, screener, logos

  /** {@code GET /v1beta1/news?symbols&limit&end&sort=desc} (Benzinga). */
  public List<NewsItem> news(Set<String> symbols, int limit, Instant before, RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.dataUrl(), "/v1beta1/news", ProviderHttp.query(
        "symbols", symbols.isEmpty() ? null : String.join(",", new TreeSet<>(symbols)),
        "limit", Math.max(1, Math.min(50, limit)), "end", before == null ? null
            : DateTimeFormatter.ISO_INSTANT.format(before), "sort", "desc"));
    List<NewsItem> out = new ArrayList<>();
    JsonNode news = root.get("news");
    if (news != null && news.isArray()) {
      for (JsonNode n : news.values()) {
        String image = null;
        JsonNode images = n.get("images");
        if (images != null && images.isArray()) {
          for (JsonNode img : images.values()) {
            if ("thumb".equals(Json.str(img, "size")) || image == null) {
              image = Json.str(img, "url");
            }
          }
        }
        Instant published = Json.instant(n, "created_at");
        out.add(new NewsItem(String.valueOf(Json.lng(n, "id", 0)), Json.str(n, "headline"), emptyToNull(
            Json.str(n, "summary")), Json.str(n, "url"), Json.str(n, "source"), Json.strings(n, "symbols"),
            published != null ? published : Json.instant(n, "updated_at"), image));
      }
    }
    return out;
  }

  /** {@code GET /v1beta1/screener/stocks/movers?top} → gainers and losers ({@code percent_change} in percent). */
  public Map<String, List<Mover>> movers(int top, RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.dataUrl(), "/v1beta1/screener/stocks/movers",
        ProviderHttp.query("top", Math.max(1, Math.min(50, top))));
    Map<String, List<Mover>> out = new LinkedHashMap<>();
    out.put("gainers", movers(root.get("gainers")));
    out.put("losers", movers(root.get("losers")));
    return out;
  }

  private static List<Mover> movers(JsonNode arr) {
    List<Mover> out = new ArrayList<>();
    if (arr == null || !arr.isArray()) {
      return out;
    }
    for (JsonNode m : arr.values()) {
      BigDecimal pct = Json.dec(m, "percent_change");
      out.add(new Mover(Json.str(m, "symbol"), Json.dec(m, "price"), Json.dec(m, "change"),
          pct == null ? null : pct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_EVEN), null));
    }
    return out;
  }

  /** {@code GET /v1beta1/screener/stocks/most-actives?by=volume&top} → {@code most_actives[{symbol, volume}]}. */
  public List<Mover> mostActives(int top, RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.dataUrl(), "/v1beta1/screener/stocks/most-actives",
        ProviderHttp.query("by", "volume", "top", Math.max(1, Math.min(100, top))));
    List<Mover> out = new ArrayList<>();
    JsonNode arr = root.get("most_actives");
    if (arr != null && arr.isArray()) {
      for (JsonNode m : arr.values()) {
        out.add(new Mover(Json.str(m, "symbol"), null, null, null, Json.lng(m, "volume", 0)));
      }
    }
    return out;
  }

  /** {@code GET /v1beta1/logos/{symbol}?placeholder=true} → PNG bytes. */
  public byte[] logo(String symbol, RefreshPriority priority) {
    return http.get(priority, props.dataUrl(), "/v1beta1/logos/" + symbol, ProviderHttp.query("placeholder", "true"))
        .body();
  }

  /** Corporate actions are read in pages of this many records (Alpaca's maximum). */
  static final int CORPORATE_ACTIONS_PAGE = 1000;
  /** At most this many pages per sync (100,000 records): a runaway token loop stops here. */
  static final int CORPORATE_ACTIONS_MAX_PAGES = 100;

  /**
   * {@code GET /v1/corporate-actions?symbols&start&end&limit&page_token} (verified against docs.alpaca.markets on
   * 2026-10-10): {@code start}/{@code end} filter on {@code process_date}; {@code next_page_token} is followed until it
   * is null. Mapping to the Phase 1 types (§14):
   * <ul>
   *   <li>{@code forward_splits}, {@code reverse_splits} → FORWARD_/REVERSE_SPLIT ({@code old_rate}, {@code new_rate},
   *       {@code ex_date}); a reverse split that also changes the symbol ({@code new_symbol}) becomes a NAME_CHANGE
   *       to the new symbol plus the split on the new symbol (NAME_CHANGE is applied first on the ex-date);</li>
   *   <li>{@code stock_dividends} ({@code rate} new shares per share) → FORWARD_SPLIT {@code 1 → 1 + rate}: the same
   *       pro-rata share increase with an unchanged total cost [VERIFY the rate convention on a live record];</li>
   *   <li>{@code cash_dividends} → CASH_DIVIDEND ({@code rate} $/share, {@code ex_date}, {@code payable_date});</li>
   *   <li>{@code name_changes} → NAME_CHANGE ({@code old_symbol} → {@code new_symbol} on {@code process_date});</li>
   *   <li>{@code cash_mergers} → CASH_MERGER, {@code stock_mergers} and {@code stock_and_cash_mergers} →
   *       STOCK_MERGER (the acquiree, on {@code effective_date}); {@code spin_offs}, {@code unit_splits} and
   *       {@code rights_distributions} → SPIN_OFF (the source symbol); {@code worthless_removals} and
   *       {@code redemptions} → DELISTING. WS3 sends these to NEEDS_ADMIN;</li>
   *   <li>other categories ({@code partial_calls}, {@code reorganizations}, {@code capital_gains_distributions}) are
   *       ignored in Phase 1.</li>
   * </ul>
   * {@code providerRef} is {@code alpaca:<id>} (plus {@code :name} for the derived rename).
   */
  public List<CorporateActionData> corporateActions(Set<String> symbols, LocalDate from, LocalDate to,
                                                    RefreshPriority priority) {
    List<CorporateActionData> out = new ArrayList<>();
    String token = null;
    for (int page = 0; page < CORPORATE_ACTIONS_MAX_PAGES; page++) {
      JsonNode root = http.getJson(priority, props.dataUrl(), "/v1/corporate-actions", ProviderHttp.query(
          "symbols", symbols.isEmpty() ? null : String.join(",", new TreeSet<>(symbols)), "start", from.toString(),
          "end", to.toString(), "limit", CORPORATE_ACTIONS_PAGE, "page_token", token));
      parseCorporateActions(root.get("corporate_actions"), out);
      token = emptyToNull(Json.str(root, "next_page_token"));
      if (token == null) {
        break;
      }
    }
    return out;
  }

  static void parseCorporateActions(JsonNode ca, List<CorporateActionData> out) {
    if (ca == null || !ca.isObject()) {
      return;
    }
    for (JsonNode s : items(ca, "forward_splits")) {
      out.add(new CorporateActionData(ref(s, "FORWARD_SPLIT", Json.str(s, "symbol"), Json.date(s, "ex_date")),
          Json.str(s, "symbol"), CorporateActionType.FORWARD_SPLIT, Json.date(s, "ex_date"),
          Json.date(s, "record_date"), Json.date(s, "payable_date"), Json.dec(s, "old_rate"),
          Json.dec(s, "new_rate"), null, null));
    }
    for (JsonNode s : items(ca, "reverse_splits")) {
      String sym = Json.str(s, "symbol");
      String renamed = emptyToNull(Json.str(s, "new_symbol"));
      LocalDate ex = Json.date(s, "ex_date");
      String ref = ref(s, "REVERSE_SPLIT", sym, ex);
      String splitSymbol = sym;
      if (renamed != null && sym != null && !renamed.equalsIgnoreCase(sym)) {
        out.add(new CorporateActionData(ref + ":name", sym, CorporateActionType.NAME_CHANGE, ex, null, null, null,
            null, null, renamed));
        splitSymbol = renamed;
      }
      out.add(new CorporateActionData(ref, splitSymbol, CorporateActionType.REVERSE_SPLIT, ex,
          Json.date(s, "record_date"), Json.date(s, "payable_date"), Json.dec(s, "old_rate"),
          Json.dec(s, "new_rate"), null, null));
    }
    for (JsonNode s : items(ca, "stock_dividends")) {
      BigDecimal rate = Json.dec(s, "rate");
      out.add(new CorporateActionData(ref(s, "STOCK_DIVIDEND", Json.str(s, "symbol"), Json.date(s, "ex_date")),
          Json.str(s, "symbol"), CorporateActionType.FORWARD_SPLIT, Json.date(s, "ex_date"),
          Json.date(s, "record_date"), Json.date(s, "payable_date"), rate == null ? null : BigDecimal.ONE,
          rate == null ? null : BigDecimal.ONE.add(rate), null, null));
    }
    for (JsonNode d : items(ca, "cash_dividends")) {
      String sym = Json.str(d, "symbol");
      LocalDate ex = Json.date(d, "ex_date");
      out.add(new CorporateActionData(ref(d, "CASH_DIVIDEND", sym, ex), sym, CorporateActionType.CASH_DIVIDEND, ex,
          Json.date(d, "record_date"), Json.date(d, "payable_date"), null, null, Json.dec(d, "rate"), null));
    }
    for (JsonNode n : items(ca, "name_changes")) {
      String oldSym = Json.str(n, "old_symbol");
      LocalDate ex = Json.date(n, "process_date");
      out.add(new CorporateActionData(ref(n, "NAME_CHANGE", oldSym, ex), oldSym, CorporateActionType.NAME_CHANGE, ex,
          null, null, null, null, null, Json.str(n, "new_symbol")));
    }
    other(ca, "cash_mergers", "acquiree_symbol", "effective_date", CorporateActionType.CASH_MERGER, out);
    other(ca, "stock_mergers", "acquiree_symbol", "effective_date", CorporateActionType.STOCK_MERGER, out);
    other(ca, "stock_and_cash_mergers", "acquiree_symbol", "effective_date", CorporateActionType.STOCK_MERGER, out);
    other(ca, "spin_offs", "source_symbol", "ex_date", CorporateActionType.SPIN_OFF, out);
    other(ca, "unit_splits", "old_symbol", "effective_date", CorporateActionType.SPIN_OFF, out);
    other(ca, "rights_distributions", "source_symbol", "ex_date", CorporateActionType.SPIN_OFF, out);
    other(ca, "worthless_removals", "symbol", "process_date", CorporateActionType.DELISTING, out);
    other(ca, "redemptions", "symbol", "process_date", CorporateActionType.DELISTING, out);
  }

  private static void other(JsonNode ca, String category, String symbolField, String dateField,
                            CorporateActionType type, List<CorporateActionData> out) {
    for (JsonNode n : items(ca, category)) {
      String sym = Json.str(n, symbolField);
      LocalDate date = Json.date(n, dateField);
      if (date == null) {
        date = Json.date(n, "process_date");
      }
      if (sym == null || date == null) {
        continue;
      }
      out.add(new CorporateActionData(ref(n, type.name(), sym, date), sym, type, date, Json.date(n, "record_date"),
          Json.date(n, "payable_date"), null, null, null, null));
    }
  }

  private static List<JsonNode> items(JsonNode ca, String category) {
    JsonNode arr = ca.get(category);
    List<JsonNode> out = new ArrayList<>();
    if (arr != null && arr.isArray()) {
      arr.values().forEach(out::add);
    }
    return out;
  }

  private static String ref(JsonNode n, String type, String symbol, LocalDate ex) {
    String id = Json.str(n, "id");
    return "alpaca:" + (id != null ? id : type + ":" + symbol + ":" + ex);
  }

  private static String emptyToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  /** {@code Timeframe} → Alpaca's identical wire value ({@code 1Min} … {@code 1Month}). */
  static String timeframe(Timeframe tf) {
    return tf.wireValue();
  }
}
