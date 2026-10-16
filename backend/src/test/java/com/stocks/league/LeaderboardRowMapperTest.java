package com.stocks.league;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.contract.portfolio.dto.LeaderboardRowDto;
import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §16.5 views (sort, ties, ranks, kinds, rank arrows) and §16.7 public rows, on synthetic entries. */
class LeaderboardRowMapperTest {

  static final Instant T0 = Instant.parse("2026-10-05T14:00:00Z");

  static LeagueEntry entry(long id, PlayerKind kind, String twr, String pnl, long openedOffset, boolean eligible,
                           boolean apiTrader, Integer previousRank) {
    PlayerRow p = new PlayerRow(id, "ply_" + id, "p" + id, "P" + id, kind, kind == PlayerKind.AI ? "Claude" : null,
        null, T0, PlayerStatus.ACTIVE, null, 0);
    return new LeagueEntry(p, 100 + id, new BigDecimal(twr), new BigDecimal(pnl), null, BigDecimal.ZERO, false,
        false, new BigDecimal("500.00"), new BigDecimal("500.00"), BigDecimal.ZERO, eligible ? 5 : 1,
        eligible ? 2 : 1, null, "SIMA", apiTrader, List.of(BigDecimal.ONE), T0.plusSeconds(openedOffset), eligible,
        eligible ? null : "1/5 trades", previousRank);
  }

  @Test
  void returnTiesWithin1e8FallBackToPnlThenFirstOpeningThenId() {
    List<LeagueEntry> entries = List.of(
        entry(1, PlayerKind.HUMAN, "0.100000001", "10.00", 5, true, false, null),
        entry(2, PlayerKind.HUMAN, "0.100000004", "20.00", 9, true, false, null),     // same return at 1e-8
        entry(3, PlayerKind.AI, "0.1", "20.00", 1, true, false, null),               // same P&L, opened first
        entry(4, PlayerKind.HUMAN, "0.5", "1.00", 0, false, false, null),            // best, but ineligible
        entry(5, PlayerKind.HUMAN, "-0.2", "-5.00", 0, true, false, null));
    List<LeaderboardRowDto> rows = LeaderboardRowMapper.rows(entries, LeaderboardMetric.RETURN, KindFilter.ALL, false);
    assertThat(rows).extracting(r -> r.player().handle()).containsExactly("p3", "p2", "p1", "p5", "p4");
    assertThat(rows).extracting(LeaderboardRowDto::rank).containsExactly(1, 2, 3, 4, null);
    assertThat(rows.get(4).eligibilityNote()).isEqualTo("1/5 trades");
  }

  @Test
  void pnlSortsByDollarsThenReturn() {
    List<LeagueEntry> entries = List.of(
        entry(1, PlayerKind.HUMAN, "0.50", "10.00", 0, true, false, null),
        entry(2, PlayerKind.HUMAN, "0.01", "90.00", 0, true, false, null),
        entry(3, PlayerKind.HUMAN, "0.02", "90.00", 0, true, false, null));
    assertThat(LeaderboardRowMapper.rows(entries, LeaderboardMetric.PNL, KindFilter.ALL, false))
        .extracting(r -> r.player().handle()).containsExactly("p3", "p2", "p1");
  }

  @Test
  void kindsAndTheApiTraderSetting() {
    LeagueEntry human = entry(1, PlayerKind.HUMAN, "0.1", "1", 0, true, false, null);
    LeagueEntry apiHuman = entry(2, PlayerKind.HUMAN, "0.2", "2", 0, true, true, null);
    LeagueEntry bot = entry(3, PlayerKind.AI, "0.3", "3", 0, true, false, null);
    List<LeagueEntry> all = List.of(human, apiHuman, bot);
    assertThat(LeaderboardRowMapper.rows(all, LeaderboardMetric.RETURN, KindFilter.HUMAN, false))
        .extracting(r -> r.player().handle()).containsExactly("p2", "p1");
    // league.humanKindExcludesApiTraders: the API-trading human is listed under AI and ALL, not HUMAN
    assertThat(LeaderboardRowMapper.rows(all, LeaderboardMetric.RETURN, KindFilter.HUMAN, true))
        .extracting(r -> r.player().handle()).containsExactly("p1");
    assertThat(LeaderboardRowMapper.rows(all, LeaderboardMetric.RETURN, KindFilter.AI, true))
        .extracting(r -> r.player().handle()).containsExactly("p3", "p2");
    assertThat(LeaderboardRowMapper.rows(all, LeaderboardMetric.RETURN, KindFilter.ALL, true)).hasSize(3);
    var arena = LeaderboardRowMapper.arena(all);
    assertThat(arena.humansMedianReturn()).isEqualByComparingTo("0.15");
    assertThat(arena.aiMedianReturn()).isEqualByComparingTo("0.3");
  }

  @Test
  void rankArrowsOnlyForReturnAndAllKinds() {
    List<LeagueEntry> entries = List.of(entry(1, PlayerKind.HUMAN, "0.2", "1", 0, true, false, 3),
        entry(2, PlayerKind.HUMAN, "0.1", "1", 0, true, false, null));
    List<LeaderboardRowDto> rows = LeaderboardRowMapper.rows(entries, LeaderboardMetric.RETURN, KindFilter.ALL, false);
    assertThat(rows.get(0).rankChange()).isEqualTo(2);              // was 3rd, now 1st
    assertThat(rows.get(1).rankChange()).isNull();                 // no previous rank
    assertThat(LeaderboardRowMapper.rows(entries, LeaderboardMetric.PNL, KindFilter.ALL, false)
        .get(0).rankChange()).isNull();
    assertThat(LeaderboardRowMapper.rows(entries, LeaderboardMetric.RETURN, KindFilter.HUMAN, false)
        .get(0).rankChange()).isNull();
  }

  @Test
  void equityAndTopHoldingAreAlwaysShownWithDeposits() {
    List<LeaderboardRowDto> rows = LeaderboardRowMapper.rows(
        List.of(entry(1, PlayerKind.HUMAN, "0.1", "1", 0, true, false, null)), LeaderboardMetric.RETURN,
        KindFilter.ALL, false);
    assertThat(rows.get(0).equity()).isEqualByComparingTo("500.00");   // public mode (§16.7)
    assertThat(rows.get(0).topHolding()).isEqualTo("SIMA");
    assertThat(rows.get(0).netContributions()).isEqualByComparingTo("500.00");
    assertThat(rows.get(0).flowsInPeriod()).isEqualByComparingTo("0");
  }

  @Test
  void eligibilityNeedsFillsAndSymbolsExceptToday() {
    assertThat(Eligibility.eligible(LeaderboardPeriod.TODAY, 0, 0, 5, 2)).isTrue();
    assertThat(Eligibility.note(LeaderboardPeriod.TODAY, 0, 0, 5, 2)).isNull();
    assertThat(Eligibility.eligible(LeaderboardPeriod.WEEK, 5, 2, 5, 2)).isTrue();
    assertThat(Eligibility.note(LeaderboardPeriod.WEEK, 3, 2, 5, 2)).isEqualTo("3/5 trades");
    assertThat(Eligibility.note(LeaderboardPeriod.ALL, 6, 1, 5, 2)).isEqualTo("1/2 symbols");
    assertThat(Eligibility.note(LeaderboardPeriod.SEASON, 0, 0, 5, 2)).isEqualTo("0/5 trades · 0/2 symbols");
  }

  @Test
  void everyRowCarriesTheDayAndTotalWhateverThePeriod() {
    LeagueEntry week = entry(1, PlayerKind.HUMAN, "0.05", "25.00", 0, true, false, null);
    LeagueEntry today = entry(1, PlayerKind.HUMAN, "0.01", "5.00", 0, true, false, null);
    LeagueEntry total = entry(1, PlayerKind.HUMAN, "0.20", "100.00", 0, true, false, null);
    LeagueEntry newcomer = entry(2, PlayerKind.AI, "0.02", "10.00", 0, true, false, null);
    List<LeaderboardRowDto> rows = LeaderboardRowMapper.rows(List.of(week, newcomer), LeaderboardMetric.RETURN,
        KindFilter.ALL, false, LeaderboardRowMapper.byPlayer(List.of(today)),
        LeaderboardRowMapper.byPlayer(List.of(total)));
    assertThat(rows.get(0).ret()).isEqualByComparingTo("0.05");
    assertThat(rows.get(0).dayReturn()).isEqualByComparingTo("0.01");
    assertThat(rows.get(0).dayPnl()).isEqualByComparingTo("5.00");
    assertThat(rows.get(0).totalReturn()).isEqualByComparingTo("0.20");
    assertThat(rows.get(0).totalPnl()).isEqualByComparingTo("100.00");
    assertThat(rows.get(1).dayReturn()).isNull();                       // not on the TODAY board: unknown
    assertThat(rows.get(1).totalPnl()).isNull();
  }
}
