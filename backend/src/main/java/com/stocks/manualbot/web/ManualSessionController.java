package com.stocks.manualbot.web;

import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.manualbot.dto.ManualSessionDto;
import com.stocks.manualbot.ManualBotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §20.8 manual sessions are public (league:read): the prompt (with the owner's instructions), the pasted reply, the
 * parsed rows, their results and the summary. REST only: raw pasted AI replies are kept off MCP (a prompt-injection
 * surface); their orders reach MCP through the player's orders and their summaries through the journal. All text is
 * inside {@code untrusted}.
 */
@RestController
@RequestMapping("/api/v1/players/{handle}/manual-sessions")
@Tag(name = "league", description = "Leaderboard, players and the league feed")
public class ManualSessionController {

  static final int DEFAULT_LIMIT = 10;
  static final int MAX_LIMIT = 50;

  private final ManualBotService manual;

  public ManualSessionController(ManualBotService manual) {
    this.manual = manual;
  }

  @GetMapping
  @Operation(summary = "A bot's manual sessions", description = "Newest first, list shape (untrusted.prompt and "
      + "untrusted.reply are null): status, summary, rows and their results. PLAYER_NOT_FOUND for an unknown or "
      + "disabled player.")
  public Page<ManualSessionDto> getPlayerManualSessions(
      @PathVariable("handle") String handle,
      @Parameter(description = "Opaque cursor from a previous page") @RequestParam(required = false) String cursor,
      @Parameter(description = "1-50, default 10") @RequestParam(required = false) Integer limit) {
    int n = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    return manual.sessions(handle, Cursor.parse(cursor), n);
  }

  @GetMapping("/{sessionId}")
  @Operation(summary = "One manual session", description = "Detail shape: the prompt and the pasted reply (redacted) "
      + "inside untrusted. NOT_FOUND when the session is not this player's.")
  public ManualSessionDto getPlayerManualSession(@PathVariable("handle") String handle,
                                                 @PathVariable("sessionId") String sessionId) {
    return manual.session(handle, sessionId);
  }
}
