package com.stocks.contract.marketdata;

import com.stocks.contract.marketdata.model.RecommendationTrend;
import java.time.Instant;
import java.util.List;

/**
 * Analyst recommendation trends of one symbol (§15.10): Finnhub's free {@code /stock/recommendation} (monthly counts of
 * strong buy … strong sell), newest period first; {@code fetchedAt} is when the provider answered.
 */
public record Recommendations(String symbol, String source, Instant fetchedAt, List<RecommendationTrend> trends) {

  public Recommendations {
    trends = trends == null ? List.of() : List.copyOf(trends);
  }
}
