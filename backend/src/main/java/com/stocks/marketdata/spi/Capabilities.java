package com.stocks.marketdata.spi;

import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a provider can do (§15.3). The startup probe (§15.8) may narrow it at runtime ({@code with…} copies).
 *
 * @param recentHistoryEmbargo how old SIP history must be (15 min on Alpaca Basic)
 * @param venueOpenEt          the live venue's hours (08:00 for IEX); null when not venue-bound (SIM, SIP)
 */
public record Capabilities(boolean realtimeTrades, boolean realtimeQuotes, boolean consolidatedQuotes,
    int maxStreamSymbols, int restCallsPerMinute, Duration recentHistoryEmbargo,
    LocalTime venueOpenEt, LocalTime venueCloseEt,
    boolean history, boolean news, boolean fundamentals, boolean corporateActions, boolean movers,
    boolean assets, boolean calendar, boolean statuses) {

  public Capabilities withMovers(boolean v) {
    return new Capabilities(realtimeTrades, realtimeQuotes, consolidatedQuotes, maxStreamSymbols, restCallsPerMinute,
        recentHistoryEmbargo, venueOpenEt, venueCloseEt, history, news, fundamentals, corporateActions, v, assets,
        calendar, statuses);
  }

  public Capabilities withNews(boolean v) {
    return new Capabilities(realtimeTrades, realtimeQuotes, consolidatedQuotes, maxStreamSymbols, restCallsPerMinute,
        recentHistoryEmbargo, venueOpenEt, venueCloseEt, history, v, fundamentals, corporateActions, movers, assets,
        calendar, statuses);
  }

  public Capabilities withStatuses(boolean v) {
    return new Capabilities(realtimeTrades, realtimeQuotes, consolidatedQuotes, maxStreamSymbols, restCallsPerMinute,
        recentHistoryEmbargo, venueOpenEt, venueCloseEt, history, news, fundamentals, corporateActions, movers, assets,
        calendar, v);
  }

  /** For {@code ProviderStatus.capabilities} on {@code /admin/system}. */
  public Map<String, Object> asMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("realtimeTrades", realtimeTrades);
    m.put("realtimeQuotes", realtimeQuotes);
    m.put("consolidatedQuotes", consolidatedQuotes);
    m.put("maxStreamSymbols", maxStreamSymbols);
    m.put("restCallsPerMinute", restCallsPerMinute);
    m.put("recentHistoryEmbargo", recentHistoryEmbargo == null ? null : recentHistoryEmbargo.toString());
    m.put("venueHoursEt", venueOpenEt == null ? null : venueOpenEt + "-" + venueCloseEt);
    m.put("history", history);
    m.put("news", news);
    m.put("fundamentals", fundamentals);
    m.put("corporateActions", corporateActions);
    m.put("movers", movers);
    m.put("assets", assets);
    m.put("calendar", calendar);
    m.put("statuses", statuses);
    return m;
  }
}
