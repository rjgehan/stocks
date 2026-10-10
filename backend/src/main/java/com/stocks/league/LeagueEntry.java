package com.stocks.league;

import com.stocks.common.money.Money;
import com.stocks.portfolio.MetricsCalculator.RiskStats;
import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One player's numbers for one period, before the view (metric sort, kind filter, ranks and §16.7 visibility) turns
 * it into a {@code LeaderboardRow}. Returns are fractions at scale 10.
 *
 * @param accountId      the run active at the window's end
 * @param firstOpenedAt  the player's first run opening (third tie-break, §16.5)
 * @param previousRank   the player's rank in the latest earlier {@code leaderboard_snapshot} (RETURN/ALL), or null
 * @param extras         Phase 2 (§16.8, §16.9): the unit-value curve, risk metrics and the profit-factor sums
 */
public record LeagueEntry(PlayerRow player, long accountId, BigDecimal twr, BigDecimal pnl, BigDecimal excess,
                          BigDecimal maxDrawdown, boolean rebased, boolean partialPeriod, BigDecimal equity,
                          BigDecimal netContributions, BigDecimal flowsInPeriod, int fills, int symbolsTraded,
                          BigDecimal winRate, String topHolding, boolean apiTrader, List<BigDecimal> spark,
                          Instant firstOpenedAt, boolean eligible, String eligibilityNote, Integer previousRank,
                          Extras extras) {

  /** The Phase 1 shape, without Phase 2 extras. */
  public LeagueEntry(PlayerRow player, long accountId, BigDecimal twr, BigDecimal pnl, BigDecimal excess,
                     BigDecimal maxDrawdown, boolean rebased, boolean partialPeriod, BigDecimal equity,
                     BigDecimal netContributions, BigDecimal flowsInPeriod, int fills, int symbolsTraded,
                     BigDecimal winRate, String topHolding, boolean apiTrader, List<BigDecimal> spark,
                     Instant firstOpenedAt, boolean eligible, String eligibilityNote, Integer previousRank) {
    this(player, accountId, twr, pnl, excess, maxDrawdown, rebased, partialPeriod, equity, netContributions,
        flowsInPeriod, fills, symbolsTraded, winRate, topHolding, apiTrader, spark, firstOpenedAt, eligible,
        eligibilityNote, previousRank, Extras.NONE);
  }

  /**
   * One point of a player's unit-value curve in the window: the anchor (an EOD or a run opening), one EOD per trading
   * day, then the end (live or the EOD of the board's day). {@code tradeDate} is null for an opening and a live end.
   */
  public record CurvePoint(Instant t, LocalDate tradeDate, BigDecimal unitValue, int unitSeries) {}

  /**
   * @param curve        anchor, EODs in the window, end (§16.9 race chart; normalized by the anchor's U)
   * @param risk         §16.8 Sharpe and volatility over the curve's daily returns (anchor + EODs, never the live end)
   * @param closingSells sells with a realized P&amp;L in the window
   * @param wins         those with a positive one
   * @param grossProfit  Σ positive realized P&amp;L (≥ 0)
   * @param grossLoss    −Σ negative realized P&amp;L (≥ 0)
   * @param profitFactor {@code grossProfit / grossLoss} (scale 4), null without a loss
   */
  public record Extras(List<CurvePoint> curve, RiskStats risk, int closingSells, int wins, BigDecimal grossProfit,
                       BigDecimal grossLoss, BigDecimal profitFactor) {
    public static final Extras NONE = new Extras(List.of(), RiskStats.NONE, 0, 0, Money.ZERO, Money.ZERO, null);
  }
}
