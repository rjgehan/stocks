package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.portfolio.dto.BenchmarkCurveDto;
import com.stocks.contract.portfolio.dto.FeedReactionsDto;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.FeedReactionDto;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.Reaction;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.ReactorDto;
import com.stocks.contract.portfolio.dto.PlayerStatsDto;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.CalendarView;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.PnlCalendarTotalDto;
import com.stocks.contract.portfolio.dto.RaceChartDto;
import com.stocks.contract.portfolio.dto.RaceChartDto.RaceSeriesDto;
import com.stocks.contract.security.Scopes;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The Phase 2 league-stats tools (§20.3 #43–#48) through the real handler: defaults, argument validation, scopes and
 * the {@code untrusted}-style clipping of other players' display names. The services are mocks; their numbers are
 * covered by {@code LeagueStatsTest}, {@code PnlAnalysisTest} and {@code FeedReactionTest}.
 */
class McpLeagueStatsToolsTest {

  McpToolHarness h;

  @BeforeEach
  void setUp() {
    h = new McpToolHarness();
  }

  @AfterEach
  void tearDown() {
    h.close();
  }

  static final String LONG_NAME = "N".repeat(80);

  static PlayerStatsDto stats(String handle) {
    PlayerSummaryDto p = new PlayerSummaryDto("ply_1", handle, LONG_NAME, PlayerKind.HUMAN, null, null,
        TestTimes.START, 0);
    return new PlayerStatsDto(p, LeaderboardPeriod.ALL, TestTimes.START, TestTimes.START, new BigDecimal("0.0100000000"),
        new BigDecimal("5.00"), BigDecimal.ZERO, false, false, null, null, 3, 20, 0, 0, null, new BigDecimal("0.00"),
        new BigDecimal("0.00"), null, 0, null, TestTimes.START, null, null, null, null, List.of());
  }

  @Test
  void statsToolsDefaultToAllAndClipDisplayNames() {
    when(h.leagueStats.myStats(anyLong(), any())).thenReturn(stats("claude"));
    JsonNode mine = McpToolHarness.ok(h.call("get_my_stats", Map.of()));
    verify(h.leagueStats).myStats(h.botPlayer.id(), LeaderboardPeriod.ALL);
    assertThat(mine.get("return").asString()).isEqualTo("0.0100000000");
    assertThat(mine.get("sharpe").isNull()).isTrue();
    assertThat(mine.get("player").get("displayName").asString()).hasSize(Untrusted.NAME_MAX);

    when(h.leagueStats.playerStats(anyString(), any(), any())).thenReturn(stats("alice"));
    McpToolHarness.ok(h.call("get_player_stats", Map.of("handle", "Alice", "period", "week")));
    verify(h.leagueStats).playerStats("alice", LeaderboardPeriod.WEEK, h.botPlayer.id());
    assertThat(McpToolHarness.error(h.call("get_player_stats", Map.of("handle", "no such!"))).get("code").asString())
        .isEqualTo("VALIDATION_FAILED");
    assertThat(McpToolHarness.error(h.call("get_my_stats", h.keyWith(Scopes.LEAGUE_READ), Map.of()))
        .get("code").asString()).isEqualTo("FORBIDDEN_SCOPE");
  }

  @Test
  void theRaceChartDefaultsToTheWeekForEveryone() {
    PlayerSummaryDto p = new PlayerSummaryDto("ply_1", "alice", LONG_NAME, PlayerKind.HUMAN, null, null,
        TestTimes.START, 0);
    when(h.leagueStats.race(any(), any())).thenReturn(new RaceChartDto(LeaderboardPeriod.WEEK, KindFilter.ALL,
        TestTimes.START, TestTimes.START, new BenchmarkCurveDto("SPY", null, List.of()),
        List.of(new RaceSeriesDto(p, 1, new BigDecimal("0.0100000000"), false, false, List.of(), List.of()))));
    JsonNode race = McpToolHarness.ok(h.call("get_race_chart", Map.of()));
    verify(h.leagueStats).race(LeaderboardPeriod.WEEK, KindFilter.ALL);
    assertThat(race.get("series").get(0).get("player").get("displayName").asString()).hasSize(Untrusted.NAME_MAX);
    McpToolHarness.ok(h.call("get_race_chart", Map.of("period", "TODAY", "kind", "AI")));
    verify(h.leagueStats).race(LeaderboardPeriod.TODAY, KindFilter.AI);
  }

  @Test
  void calendarAndAnalysisValidateTheirArguments() {
    when(h.pnlAnalysis.calendar(anyLong(), any(), any(), any())).thenReturn(new PnlCalendarDto(CalendarView.DAY,
        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), TestTimes.START, List.of(),
        new PnlCalendarTotalDto(new BigDecimal("0.00"), null)));
    McpToolHarness.ok(h.call("get_pnl_calendar", Map.of()));
    verify(h.pnlAnalysis).calendar(anyLong(), eq(CalendarView.DAY), eq(null), eq(null));
    McpToolHarness.ok(h.call("get_pnl_calendar", Map.of("view", "month", "year", 2026)));
    verify(h.pnlAnalysis).calendar(anyLong(), eq(CalendarView.MONTH), eq(null), eq(2026));
    McpToolHarness.ok(h.call("get_pnl_calendar", Map.of("month", "2026-09")));
    verify(h.pnlAnalysis).calendar(anyLong(), eq(CalendarView.DAY), eq(YearMonth.of(2026, 9)), eq(null));
    for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("view", "WEEK"), Map.of("month", "2026-13"),
        Map.of("month", "Sept"), Map.of("month", "1999-12"), Map.of("year", 3000))) {
      assertThat(McpToolHarness.error(h.call("get_pnl_calendar", bad)).get("code").asString()).as(bad.toString())
          .isEqualTo("VALIDATION_FAILED");
    }

    when(h.pnlAnalysis.analysis(anyLong(), any())).thenReturn(new PnlAnalysisDto(HistoryRange.M1, TestTimes.START,
        TestTimes.START, new BigDecimal("0.0000000000"), new BigDecimal("0.00"), List.of(), List.of(), List.of(),
        new BigDecimal("0.00")));
    McpToolHarness.ok(h.call("get_pnl_analysis", Map.of()));
    verify(h.pnlAnalysis).analysis(anyLong(), eq(HistoryRange.M1));
    McpToolHarness.ok(h.call("get_pnl_analysis", Map.of("range", "ytd")));
    verify(h.pnlAnalysis).analysis(anyLong(), eq(HistoryRange.YTD));
  }

  @Test
  void botsReactWithTradeScopeAndNoneRemovesTheirs() {
    FeedReactionsDto dto = new FeedReactionsDto("fde_1", List.of(new FeedReactionDto(Reaction.ROCKET, 1,
        List.of(new ReactorDto("claude", LONG_NAME, PlayerKind.AI)))));
    when(h.reactions.react(anyLong(), anyString(), any())).thenReturn(dto);
    when(h.reactions.unreact(anyLong(), anyString())).thenReturn(new FeedReactionsDto("fde_1", List.of()));
    JsonNode r = McpToolHarness.ok(h.call("react_to_feed_item", Map.of("item_id", "fde_1", "reaction", "rocket")));
    verify(h.reactions).react(h.botPlayer.id(), "fde_1", Reaction.ROCKET);
    assertThat(r.get("reactions").get(0).get("players").get(0).get("displayName").asString())
        .hasSize(Untrusted.NAME_MAX);
    JsonNode none = McpToolHarness.ok(h.call("react_to_feed_item", Map.of("item_id", "fde_1", "reaction", "NONE")));
    verify(h.reactions).unreact(h.botPlayer.id(), "fde_1");
    assertThat(none.get("reactions").isEmpty()).isTrue();
    assertThat(McpToolHarness.error(h.call("react_to_feed_item", Map.of("item_id", "fde_1", "reaction", "HEART")))
        .get("code").asString()).isEqualTo("VALIDATION_FAILED");
    assertThat(McpToolHarness.error(h.call("react_to_feed_item", Map.of("reaction", "GEM"))).get("code").asString())
        .isEqualTo("VALIDATION_FAILED");
    assertThat(McpToolHarness.error(h.call("react_to_feed_item",
        h.keyWith(Scopes.MARKET_READ, Scopes.ACCOUNT_READ, Scopes.LEAGUE_READ),
        Map.of("item_id", "fde_1", "reaction", "GEM"))).get("code").asString()).isEqualTo("FORBIDDEN_SCOPE");
    verify(h.reactions, never()).react(anyLong(), anyString(), eq(Reaction.GEM));
  }
}
