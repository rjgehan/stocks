package com.stocks.apilog.web;

import com.stocks.apilog.ApiCallService;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.agents.dto.ApiCallDto;
import com.stocks.contract.security.CurrentPlayer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §17.10 "Recent API calls": {@code GET /me/api-calls} (session only, the {@code /me/**} rule) for the caller's keys
 * and the AI players they own, and {@code GET /admin/api-calls} (admin chain) for everyone. One row per hourly
 * bucket of (key, channel, endpoint, outcome), newest last call first.
 */
@RestController
public class ApiCallsController {

  private final ApiCallService calls;

  public ApiCallsController(ApiCallService calls) {
    this.calls = calls;
  }

  @GetMapping("/api/v1/me/api-calls")
  @Tag(name = "me", description = "The authenticated player")
  @Operation(summary = "Recent API calls of my keys and bots", description = "Session only. Hourly buckets of the "
      + "calls made with your API keys and those of the AI players you own (REST and MCP), newest first; handle "
      + "narrows to one of them. Kept 90 days.")
  public Page<ApiCallDto> myApiCalls(
      @Parameter(description = "Your handle or one of your bots'") @RequestParam(required = false) String handle,
      @Parameter(description = "Opaque cursor from a previous page") @RequestParam(required = false) String cursor,
      @Parameter(description = "1-200, default 50") @RequestParam(required = false) Integer limit) {
    return calls.mine(CurrentPlayer.require().playerId(), handle, Cursor.parse(cursor), Page.clampLimit(limit));
  }

  @GetMapping("/api/v1/admin/api-calls")
  @Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
  @Operation(summary = "Recent API calls of every player", description = "Hourly buckets of every API key's calls "
      + "(REST and MCP), newest first; handle narrows to one player.")
  public Page<ApiCallDto> adminApiCalls(
      @Parameter(description = "A player's handle") @RequestParam(required = false) String handle,
      @Parameter(description = "Opaque cursor from a previous page") @RequestParam(required = false) String cursor,
      @Parameter(description = "1-200, default 50") @RequestParam(required = false) Integer limit) {
    return calls.all(handle, Cursor.parse(cursor), Page.clampLimit(limit));
  }
}
