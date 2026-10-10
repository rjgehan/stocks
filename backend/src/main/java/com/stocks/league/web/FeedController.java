package com.stocks.league.web;

import com.stocks.common.error.ApiException;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.dto.FeedItemDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §18.2 {@code GET /feed} ({@code league:read}); the same contract method as MCP {@code get_league_feed}. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "league", description = "Leaderboard, players and the league feed")
public class FeedController {

  private final PerformanceQueries performance;

  public FeedController(PerformanceQueries performance) {
    this.performance = performance;
  }

  @GetMapping("/feed")
  @Operation(summary = "The league feed", description = "Fills (after the 15-minute delay), closed positions, "
      + "deposits, resets, new leaders and new players; newest first. Player text is inside `untrusted`.")
  public Page<FeedItemDto> getFeed(
      @RequestParam(name = "cursor", required = false) String cursor,
      @Parameter(description = "1..200, default 50")
      @RequestParam(name = "limit", required = false) Integer limit) {
    if (limit != null && (limit < 1 || limit > Page.MAX_LIMIT)) {
      throw ApiException.validation("limit", "limit must be between 1 and 200");
    }
    return performance.feed(Cursor.parse(cursor), Page.clampLimit(limit));
  }
}
