package com.stocks.agents.web;

import com.stocks.agents.AgentRunService;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.agents.dto.AdminBotDto;
import com.stocks.contract.agents.dto.AgentRunDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §21.1 Admin → Bots (§20.6): every AI player's runs, spend against the runner's daily cap and tool calls. */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
public class AdminBotsController {

  private final AgentRunService runs;

  public AdminBotsController(AgentRunService runs) {
    this.runs = runs;
  }

  @GetMapping("/bots")
  @Operation(summary = "AI players and their runs", description = "Per AI player (DISABLED ones last): last run, "
      + "last problem run, today's spend against the daily cap, the last 14 ET days and the 7-day tool breakdown.")
  public List<AdminBotDto> bots() {
    return runs.adminBots();
  }

  @GetMapping("/agent-runs")
  @Operation(summary = "Agent runs", description = "Every reported run, newest first; filter by bot handle and "
      + "status. Paged with cursor/limit (default 50, max 200).")
  public Page<AgentRunDto> agentRuns(
      @Parameter(description = "A bot's handle") @RequestParam(required = false) String handle,
      @Parameter(description = "A run status, e.g. ERROR") @RequestParam(required = false) String status,
      @Parameter(description = "Opaque cursor from a previous page") @RequestParam(required = false) String cursor,
      @Parameter(description = "1-200, default 50") @RequestParam(required = false) Integer limit) {
    return runs.adminRuns(handle, status, Cursor.parse(cursor), Page.clampLimit(limit));
  }
}
