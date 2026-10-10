package com.stocks.contract.marketdata.dto;

import com.stocks.contract.marketdata.Recommendations;
import com.stocks.contract.marketdata.model.RecommendationTrend;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * §15.10 / §18.3 {@code Recommendations} ({@code GET /assets/{symbol}/recommendations}, MCP
 * {@code get_recommendations}): monthly analyst rating counts, newest period first; {@code periods} is empty and
 * {@code source} is {@code none} when no source covers the symbol (the UI hides the card).
 */
public record RecommendationsDto(String symbol, String source, Instant asOf, List<RecommendationPeriodDto> periods) {

  /** One month (period = the first day of the month); {@code total} = the sum of the five counts. */
  public record RecommendationPeriodDto(LocalDate period, int strongBuy, int buy, int hold, int sell, int strongSell,
                                        int total) {

    public static RecommendationPeriodDto of(RecommendationTrend t) {
      return new RecommendationPeriodDto(t.period(), t.strongBuy(), t.buy(), t.hold(), t.sell(), t.strongSell(),
          t.total());
    }
  }

  /** The mapping every channel uses (REST, MCP). */
  public static RecommendationsDto of(String symbol, Optional<Recommendations> r) {
    if (r.isEmpty()) {
      return new RecommendationsDto(symbol, "none", null, List.of());
    }
    Recommendations rec = r.get();
    return new RecommendationsDto(rec.symbol(), rec.source(), rec.fetchedAt(),
        rec.trends().stream().map(RecommendationPeriodDto::of).toList());
  }
}
