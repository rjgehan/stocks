package com.stocks.marketdata.alpaca;

import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.marketdata.core.EtSchedule;
import com.stocks.marketdata.core.ProviderHttp;
import com.stocks.marketdata.spi.ProviderException;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The startup capability probe (§15.8, {@code ALPACA_PROBE}: at boot and daily at 03:10 ET). It asks Alpaca what this
 * key's plan allows and narrows the provider's capabilities: {@code snapshots?feed=delayed_sip}, the movers and
 * most-actives screeners (else movers are computed), news (else the news tab hides or falls back to Finnhub), the
 * snapshot batch maximum (200 symbols in one call, else 100), and the trading API ({@code /v2/clock},
 * {@code /v2/calendar}, {@code /v2/assets}). Each result is published to {@code /admin/system} through the
 * {@code results} sink. Stream checks that would need a second connection (the {@code /v2/test} 31st subscribe, a
 * {@code statuses} trial) are not run, because a second connection on the same key risks error 406 on the live
 * stream; {@code statuses} is requested on the live stream instead and dropped if refused.
 */
public class AlpacaCapabilityProbe implements ScheduledJob {

  private static final Logger log = LoggerFactory.getLogger(AlpacaCapabilityProbe.class);
  static final LocalTime AT = LocalTime.of(3, 10);

  private final AlpacaMarketDataProvider alpaca;
  private final ProviderHttp http;
  private final String dataUrl;
  private final String tradingUrl;
  private final Supplier<AlpacaStreamClient> stream;
  private final BiConsumer<String, Object> results;
  private final TimeSource time;
  private volatile boolean screener = true;
  private volatile Instant lastRunAt;

  public AlpacaCapabilityProbe(AlpacaMarketDataProvider alpaca, ProviderHttp http, String dataUrl, String tradingUrl,
                               Supplier<AlpacaStreamClient> stream, BiConsumer<String, Object> results,
                               TimeSource time) {
    this.alpaca = alpaca;
    this.http = http;
    this.dataUrl = dataUrl;
    this.tradingUrl = tradingUrl;
    this.stream = stream;
    this.results = results;
    this.time = time;
  }

  @Override
  public String name() {
    return "ALPACA_PROBE";
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    return EtSchedule.daily(fromExclusive, toInclusive, AT, d -> true);
  }

  @Override
  public void run(String runKey) {
    probe();
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.LATEST_ONLY;
  }

  /** Whether the screener endpoints answered (movers fall back to {@code computed} otherwise). */
  public boolean screenerAvailable() {
    return screener;
  }

  public Instant lastRunAt() {
    return lastRunAt;
  }

  /** Runs every check; never throws. Returns the results by check name. */
  public Map<String, Object> probe() {
    Map<String, Object> r = new LinkedHashMap<>();
    String feed = alpaca.feed();
    boolean clock = check(r, "tradingApiClock", () -> http.getJson(RefreshPriority.BACKGROUND, tradingUrl, "/v2/clock",
        Map.of()));
    boolean delayed = check(r, "snapshotsDelayedSip", () -> http.getJson(RefreshPriority.BACKGROUND, dataUrl,
        "/v2/stocks/snapshots", ProviderHttp.query("symbols", "SPY", "feed", "delayed_sip")));
    boolean snapshots = check(r, "snapshots" + feed.toUpperCase(java.util.Locale.ROOT), () -> http.getJson(
        RefreshPriority.BACKGROUND, dataUrl, "/v2/stocks/snapshots", ProviderHttp.query("symbols", "SPY",
            "feed", feed)));
    boolean movers = check(r, "movers", () -> http.getJson(RefreshPriority.BACKGROUND, dataUrl,
        "/v1beta1/screener/stocks/movers", ProviderHttp.query("top", 5)));
    boolean actives = check(r, "mostActives", () -> http.getJson(RefreshPriority.BACKGROUND, dataUrl,
        "/v1beta1/screener/stocks/most-actives", ProviderHttp.query("by", "volume", "top", 5)));
    boolean news = check(r, "news", () -> http.getJson(RefreshPriority.BACKGROUND, dataUrl, "/v1beta1/news",
        ProviderHttp.query("limit", 1)));
    boolean big = snapshots && check(r, "snapshotBatch200", () -> http.getJson(RefreshPriority.BACKGROUND, dataUrl,
        "/v2/stocks/snapshots", ProviderHttp.query("symbols", String.join(",", syntheticSymbols(200)), "feed", feed)));
    screener = movers && actives;
    alpaca.updateCapabilities(alpaca.capabilities().withMovers(screener).withNews(news));
    alpaca.setSnapshotBatch(big ? 200 : 100);
    r.put("snapshotBatch", alpaca.snapshotBatch());
    AlpacaStreamClient s = stream.get();
    if (s != null) {
      s.setStatusesWanted(true);
      r.put("statuses", "requested on the live stream (dropped automatically if the plan refuses it)");
      r.put("streamQuoteSizeMultiplier", s.quoteSizeMultiplier());
    }
    r.put("stream31stSubscribe", "not probed: a second connection on this key risks error 406");
    r.put("ranAt", time.now().toString());
    r.forEach(results);
    lastRunAt = time.now();
    log.info("Alpaca capability probe: clock={} delayedSip={} snapshots={} movers={} mostActives={} news={} batch={}",
        clock, delayed, snapshots, movers, actives, news, alpaca.snapshotBatch());
    return r;
  }

  private static boolean check(Map<String, Object> r, String name, Supplier<JsonNode> call) {
    try {
      call.get();
      r.put(name, "ok");
      return true;
    } catch (ProviderException e) {
      r.put(name, e.status() == 0 ? "unreachable" : "HTTP " + e.status());
      return false;
    } catch (RuntimeException e) {
      r.put(name, "error: " + e.getClass().getSimpleName());
      return false;
    }
  }

  /** Popular symbols for the batch-size check (Alpaca ignores unknown ones). */
  static List<String> syntheticSymbols(int n) {
    Set<String> out = new LinkedHashSet<>(List.of("SPY", "QQQ", "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA",
        "IWM", "DIA"));
    List<String> letters = new ArrayList<>();
    for (char a = 'A'; a <= 'Z' && out.size() + letters.size() < n; a++) {
      for (char b = 'A'; b <= 'Z' && out.size() + letters.size() < n; b++) {
        letters.add("" + a + b);
      }
    }
    out.addAll(letters);
    return List.copyOf(out).subList(0, Math.min(n, out.size()));
  }
}
