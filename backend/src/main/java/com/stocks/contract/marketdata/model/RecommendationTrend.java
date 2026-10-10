package com.stocks.contract.marketdata.model;

import java.time.LocalDate;

/** One month of analyst recommendations (§15.10, Finnhub {@code /stock/recommendation}): counts per rating. */
public record RecommendationTrend(String symbol, LocalDate period, int strongBuy, int buy, int hold, int sell,
                                  int strongSell) {

  public int total() {
    return strongBuy + buy + hold + sell + strongSell;
  }
}
