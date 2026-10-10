package com.stocks.league;

import com.stocks.common.domain.LeaderboardPeriod;
import java.util.ArrayList;
import java.util.List;

/**
 * §16.5 eligibility: {@code WEEK}, {@code MONTH}, {@code YTD}, {@code SEASON} and {@code ALL} need at least
 * {@code league.eligibility.minFills} fills (orders with a fill whose first execution falls in the window, across
 * runs) across at least {@code league.eligibility.minSymbols} distinct symbols; {@code TODAY} has no minimum.
 * Ineligible rows are listed unranked with a note such as {@code "3/5 trades"}.
 */
public final class Eligibility {

  private Eligibility() {}

  public static boolean eligible(LeaderboardPeriod period, int fills, int symbols, int minFills, int minSymbols) {
    return period == LeaderboardPeriod.TODAY || (fills >= minFills && symbols >= minSymbols);
  }

  /** Null when eligible; otherwise what is missing, e.g. {@code "3/5 trades · 1/2 symbols"}. */
  public static String note(LeaderboardPeriod period, int fills, int symbols, int minFills, int minSymbols) {
    if (eligible(period, fills, symbols, minFills, minSymbols)) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    if (fills < minFills) {
      parts.add(fills + "/" + minFills + " trades");
    }
    if (symbols < minSymbols) {
      parts.add(symbols + "/" + minSymbols + " symbols");
    }
    return String.join(" · ", parts);
  }
}
