package com.stocks.league;

import com.stocks.common.domain.HoldingsVisibility;
import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.PlayerKind;
import com.stocks.contract.portfolio.dto.LeaderboardDto.ArenaDto;
import com.stocks.contract.portfolio.dto.LeaderboardRowDto;
import com.stocks.portfolio.MetricsCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns {@link LeagueEntry} values into §18.3 {@code LeaderboardRow}s for one view (§16.5): kind filter, sort by
 * metric, ranks for eligible rows (ineligible rows follow, unranked), rank change and the §16.7 visibility rules.
 * This is the only place leaderboard visibility is applied: {@code equity} and {@code topHolding} are null under
 * {@code HIDDEN}; net contributions and flows are always public (charter item 2).
 */
public final class LeaderboardRowMapper {

  /** TWR ties are "equal within 1e-8" (§16.5): compared at 8 decimals so the order stays transitive. */
  static final int TIE_SCALE = 8;

  private LeaderboardRowMapper() {}

  /**
   * @param humanExcludesApiTraders {@code league.humanKindExcludesApiTraders}: a human with an API-key fill in the
   *                                window is listed under AI and ALL, not HUMAN
   */
  public static boolean inKind(LeagueEntry e, KindFilter kind, boolean humanExcludesApiTraders) {
    boolean countsAsAi = e.player().kind() == PlayerKind.AI || (humanExcludesApiTraders && e.apiTrader());
    return switch (kind) {
      case ALL -> true;
      case HUMAN -> !countsAsAi;
      case AI -> countsAsAi;
    };
  }

  /**
   * §16.5 order: RETURN by TWR (ties within 1e-8), then P&amp;L, then the player's first opening, then player id;
   * PNL by P&amp;L, then TWR, then the same tie-breaks.
   */
  public static Comparator<LeagueEntry> order(LeaderboardMetric metric) {
    Comparator<LeagueEntry> byReturn = Comparator.comparing(
        (LeagueEntry e) -> e.twr().setScale(TIE_SCALE, RoundingMode.HALF_EVEN)).reversed();
    Comparator<LeagueEntry> byPnl = Comparator.comparing(LeagueEntry::pnl).reversed();
    Comparator<LeagueEntry> rest = Comparator.comparing(LeagueEntry::firstOpenedAt)
        .thenComparingLong(e -> e.player().id());
    return metric == LeaderboardMetric.PNL ? byPnl.thenComparing(byReturn).thenComparing(rest)
        : byReturn.thenComparing(byPnl).thenComparing(rest);
  }

  /** Every row of the view in order: ranked (eligible) rows first, then the unranked ones. */
  public static List<LeaderboardRowDto> rows(List<LeagueEntry> entries, LeaderboardMetric metric, KindFilter kind,
                                             boolean humanExcludesApiTraders, HoldingsVisibility visibility) {
    List<LeagueEntry> filtered = new ArrayList<>(entries.stream()
        .filter(e -> inKind(e, kind, humanExcludesApiTraders)).toList());
    filtered.sort(Comparator.comparing((LeagueEntry e) -> !e.eligible()).thenComparing(order(metric)));
    boolean showRankChange = metric == LeaderboardMetric.RETURN && kind == KindFilter.ALL;
    boolean hidden = visibility == HoldingsVisibility.HIDDEN;
    List<LeaderboardRowDto> out = new ArrayList<>();
    int rank = 0;
    for (LeagueEntry e : filtered) {
      Integer r = e.eligible() ? ++rank : null;
      Integer change = showRankChange && r != null && e.previousRank() != null ? e.previousRank() - r : null;
      out.add(new LeaderboardRowDto(r, change, e.eligible(), e.eligibilityNote(), e.partialPeriod(), e.rebased(),
          e.player().summary(), e.apiTrader(), e.twr(), e.pnl(), e.excess(), e.maxDrawdown(),
          hidden ? null : e.equity(), e.netContributions(), e.flowsInPeriod(), e.fills(), e.symbolsTraded(),
          e.winRate(), hidden ? null : e.topHolding(), e.spark(), e.extras().risk().sharpe(),
          e.extras().profitFactor()));
    }
    return out;
  }

  /** The Arena card: median TWR of humans vs AI players among {@code entries} (every listed player). */
  public static ArenaDto arena(List<LeagueEntry> entries) {
    List<BigDecimal> humans = new ArrayList<>();
    List<BigDecimal> ai = new ArrayList<>();
    for (LeagueEntry e : entries) {
      (e.player().kind() == PlayerKind.AI ? ai : humans).add(e.twr());
    }
    return new ArenaDto(MetricsCalculator.median(humans), MetricsCalculator.median(ai));
  }
}
