package com.stocks.agents.web;

import com.stocks.agents.PlanUsageService;
import com.stocks.contract.agents.dto.PlanUsageDto;
import com.stocks.contract.agents.dto.PlanUsageReportRequest;
import com.stocks.contract.security.CurrentPlayer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Claude plan usage: the claude-trader's snapshots of the owner's plan meters around check-ins (a bot's key, trade
 * scope) and the owner's Claude plan panel (session only, under {@code /me/**}).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "agents", description = "AI players: run reports and the public AI journal (Phase 2)")
public class PlanUsageController {

  private final PlanUsageService usage;

  public PlanUsageController(PlanUsageService usage) {
    this.usage = usage;
  }

  @PostMapping("/agent-runs/plan-usage")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Report a Claude plan usage snapshot", description = "Sent by the claude-trader with a bot's "
      + "own API key (trade scope; AI players only) before and after each check-in and each round of check-ins: the "
      + "plan's 5-hour session and weekly meters (0-100) as Claude Code's /usage reports them, or as the check-in's "
      + "own rate-limit reading. At most 500 per bot in 24 hours.")
  public PlanUsageDto reportPlanUsage(@RequestBody PlanUsageReportRequest body) {
    return usage.report(CurrentPlayer.require(), body);
  }

  @GetMapping("/me/bots/plan-usage")
  @Tag(name = "me", description = "The authenticated player")
  @Operation(summary = "My bots' Claude plan usage", description = "Session only. The plan usage snapshots of the "
      + "last `days` days (1-14, default 7) sent with the keys of the bots the caller owns (every AI player's for "
      + "the admin), newest first, at most 2000.")
  public List<PlanUsageDto> getPlanUsage(
      @Parameter(description = "1-14, default 7") @RequestParam(name = "days", required = false) Integer days) {
    return usage.list(CurrentPlayer.require(), days);
  }
}
