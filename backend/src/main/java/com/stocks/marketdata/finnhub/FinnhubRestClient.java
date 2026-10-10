package com.stocks.marketdata.finnhub;

import com.stocks.common.domain.AssetType;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.contract.marketdata.model.NewsItem;
import com.stocks.contract.marketdata.model.RecommendationTrend;
import com.stocks.marketdata.spi.CompanyProfile;
import com.stocks.marketdata.config.MarketDataProperties;
import com.stocks.marketdata.core.Json;
import com.stocks.marketdata.core.ProviderHttp;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Finnhub REST (§15.6, header {@code X-Finnhub-Token}): {@code /stock/profile2} and {@code /stock/metric?metric=all}
 * (fundamentals), {@code /stock/symbol?exchange=US} (security types), {@code /search} and {@code /company-news}. 55
 * calls/min and 30/s through {@link ProviderHttp}.
 */
public class FinnhubRestClient {

  public static final String ID = "finnhub";
  static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

  private final MarketDataProperties.Finnhub props;
  private final ProviderHttp http;

  public FinnhubRestClient(MarketDataProperties.Finnhub props, ProviderHttp http) {
    this.props = props;
    this.http = http;
  }

  public ProviderHttp http() {
    return http;
  }

  /**
   * {@code profile2} ({@code marketCapitalization} and {@code shareOutstanding} are in millions) plus {@code metric}
   * ({@code 52WeekHigh}, {@code 52WeekLow}, {@code beta}, P/E, EPS, dividend yield in percent).
   */
  public Optional<Fundamentals> fundamentals(String symbol, RefreshPriority priority) {
    JsonNode profile = http.getJson(priority, props.baseUrl(), "/stock/profile2", ProviderHttp.query("symbol", symbol));
    JsonNode metricRoot = http.getJson(priority, props.baseUrl(), "/stock/metric",
        ProviderHttp.query("symbol", symbol, "metric", "all"));
    JsonNode metric = metricRoot.get("metric");
    if ((profile == null || profile.isEmpty()) && (metric == null || metric.isEmpty())) {
      return Optional.empty();
    }
    BigDecimal capM = Json.dec(profile, "marketCapitalization");
    BigDecimal sharesM = Json.dec(profile, "shareOutstanding");
    BigDecimal yieldPct = first(metric, "dividendYieldIndicatedAnnual", "currentDividendYieldTTM");
    return Optional.of(new Fundamentals(symbol,
        capM == null ? null : capM.multiply(MILLION).setScale(0, RoundingMode.HALF_EVEN),
        scale(first(metric, "peTTM", "peBasicExclExtraTTM", "peExclExtraTTM"), 2),
        scale(first(metric, "epsTTM", "epsBasicExclExtraItemsTTM", "epsExclExtraItemsTTM"), 2),
        yieldPct == null ? null : yieldPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_EVEN),
        scale(Json.dec(metric, "52WeekHigh"), 4), scale(Json.dec(metric, "52WeekLow"), 4),
        scale(Json.dec(metric, "beta"), 2),
        sharesM == null ? null : sharesM.multiply(MILLION).setScale(0, RoundingMode.HALF_EVEN),
        Json.str(profile, "finnhubIndustry"), Json.str(profile, "weburl"), ID));
  }

  /**
   * {@code /stock/profile2} only (§15.10 screener; one call): {@code shareOutstanding} and
   * {@code marketCapitalization} are in millions, {@code finnhubIndustry} is Finnhub's industry label. Empty when
   * Finnhub returns {@code {}} (ETFs, unknown symbols).
   */
  public Optional<CompanyProfile> profile(String symbol, RefreshPriority priority) {
    JsonNode profile = http.getJson(priority, props.baseUrl(), "/stock/profile2", ProviderHttp.query("symbol", symbol));
    if (profile == null || profile.isEmpty() || !profile.isObject()) {
      return Optional.empty();
    }
    BigDecimal capM = Json.dec(profile, "marketCapitalization");
    BigDecimal sharesM = Json.dec(profile, "shareOutstanding");
    String industry = Json.str(profile, "finnhubIndustry");
    if (capM == null && sharesM == null && (industry == null || industry.isBlank())) {
      return Optional.empty();
    }
    return Optional.of(new CompanyProfile(symbol,
        sharesM == null || sharesM.signum() <= 0 ? null : sharesM.multiply(MILLION).setScale(0, RoundingMode.HALF_EVEN),
        capM == null || capM.signum() <= 0 ? null : capM.multiply(MILLION).setScale(0, RoundingMode.HALF_EVEN),
        industry == null || industry.isBlank() || "N/A".equalsIgnoreCase(industry.trim()) ? null : industry.trim(),
        ID));
  }

  /**
   * {@code /stock/recommendation?symbol} (§15.10, free tier): monthly counts per rating, as Finnhub sends them
   * (newest first). {@code period} is the first day of the month ({@code YYYY-MM-DD}).
   */
  public List<RecommendationTrend> recommendations(String symbol, RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.baseUrl(), "/stock/recommendation",
        ProviderHttp.query("symbol", symbol));
    List<RecommendationTrend> out = new ArrayList<>();
    if (root == null || !root.isArray()) {
      return out;
    }
    for (JsonNode r : root.values()) {
      LocalDate period = Json.date(r, "period");
      if (period == null) {
        continue;
      }
      out.add(new RecommendationTrend(symbol, period, count(r, "strongBuy"), count(r, "buy"), count(r, "hold"),
          count(r, "sell"), count(r, "strongSell")));
    }
    return out;
  }

  private static int count(JsonNode n, String field) {
    long v = Json.lng(n, field, 0);
    return (int) Math.max(0, Math.min(10_000, v));
  }

  private static BigDecimal first(JsonNode n, String... fields) {
    for (String f : fields) {
      BigDecimal v = Json.dec(n, f);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  private static BigDecimal scale(BigDecimal v, int scale) {
    return v == null ? null : v.setScale(scale, RoundingMode.HALF_EVEN);
  }

  /** {@code /stock/symbol?exchange=US}: Common Stock → COMMON_STOCK, ETP → ETF, ADR → ADR, else OTHER. */
  public Map<String, AssetType> assetTypes(RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.baseUrl(), "/stock/symbol", ProviderHttp.query("exchange", "US"));
    Map<String, AssetType> out = new LinkedHashMap<>();
    for (JsonNode s : root.values()) {
      String sym = Json.str(s, "symbol");
      if (sym != null) {
        out.put(sym.toUpperCase(Locale.ROOT), type(Json.str(s, "type")));
      }
    }
    return out;
  }

  static AssetType type(String finnhubType) {
    if (finnhubType == null) {
      return AssetType.OTHER;
    }
    return switch (finnhubType) {
      case "Common Stock" -> AssetType.COMMON_STOCK;
      case "ETP" -> AssetType.ETF;
      case "ADR" -> AssetType.ADR;
      default -> AssetType.OTHER;
    };
  }

  /** {@code /search?q&exchange=US} → symbols with descriptions. */
  public List<String[]> search(String query, RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.baseUrl(), "/search", ProviderHttp.query("q", query, "exchange", "US"));
    List<String[]> out = new ArrayList<>();
    JsonNode result = root.get("result");
    if (result != null && result.isArray()) {
      for (JsonNode r : result.values()) {
        out.add(new String[] {Json.str(r, "symbol"), Json.str(r, "description"), Json.str(r, "type")});
      }
    }
    return out;
  }

  /** {@code /company-news?symbol&from&to} (the news fallback; {@code datetime} is epoch seconds). */
  public List<NewsItem> companyNews(String symbol, LocalDate from, LocalDate to, RefreshPriority priority) {
    JsonNode root = http.getJson(priority, props.baseUrl(), "/company-news",
        ProviderHttp.query("symbol", symbol, "from", from.toString(), "to", to.toString()));
    List<NewsItem> out = new ArrayList<>();
    for (JsonNode n : root.values()) {
      long epoch = Json.lng(n, "datetime", 0);
      String related = Json.str(n, "related");
      out.add(new NewsItem("fh-" + Json.lng(n, "id", 0), Json.str(n, "headline"), Json.str(n, "summary"),
          Json.str(n, "url"), Json.str(n, "source"), related == null || related.isBlank() ? List.of(symbol)
              : List.of(related.split(",")), Instant.ofEpochSecond(epoch), emptyToNull(Json.str(n, "image"))));
    }
    return out;
  }

  private static String emptyToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
