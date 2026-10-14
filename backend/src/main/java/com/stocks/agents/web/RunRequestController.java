package com.stocks.agents.web;

import com.stocks.agents.RunRequestService;
import com.stocks.contract.agents.dto.FinishRunRequest;
import com.stocks.contract.agents.dto.RunNowRequest;
import com.stocks.contract.agents.dto.RunRequestDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §20.9 "Run now": the bot's owner or the admin asks for a check-in on a browser session ({@code /me/**}: session
 * only, CSRF), and the claude-trader service claims and finishes it with the bot's own key ({@code trade} scope, AI
 * players only, like {@code POST /agent-runs}; route rules in {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1")
public class RunRequestController {

  private final RunRequestService requests;

  public RunRequestController(RunRequestService requests) {
    this.requests = requests;
  }

  @PostMapping("/me/bots/{handle}/run-requests")
  @ResponseStatus(HttpStatus.CREATED)
  @Tag(name = "me", description = "The authenticated player")
  @Operation(summary = "Run a bot's check-in now", description = "Session only; the bot's owner or the admin. The "
      + "claude-trader service picks the request up within about a minute and runs the bot's normal check-in "
      + "(dryRun: the order tools are blocked). One open request per bot (409 RUN_REQUEST_CONFLICT while one is "
      + "PENDING or CLAIMED); at most 20 per bot in 24 hours (RATE_LIMITED). A request nobody picks up within 30 "
      + "minutes becomes EXPIRED. Errors: PLAYER_NOT_FOUND, FORBIDDEN (not an AI player, not yours), PLAYER_FROZEN, "
      + "RUN_REQUEST_CONFLICT, RATE_LIMITED.")
  public RunRequestDto requestRun(@PathVariable("handle") String handle,
                                  @RequestBody(required = false) RunNowRequest body, HttpServletRequest request) {
    PlayerPrincipal caller = CurrentPlayer.require();
    return requests.request(caller, handle, body, RequestMetas.of(request, caller));
  }

  @GetMapping("/me/bots/{handle}/run-requests")
  @Tag(name = "me", description = "The authenticated player")
  @Operation(summary = "A bot's run requests", description = "Session only; the bot's owner or the admin. The latest "
      + "requests, newest first (limit 1-50, default 10).")
  public List<RunRequestDto> listRunRequests(
      @PathVariable("handle") String handle,
      @Parameter(description = "1-50, default 10") @RequestParam(required = false) Integer limit) {
    return requests.list(CurrentPlayer.require(), handle, limit);
  }

  @PostMapping("/agent-runs/requests/claim")
  @Tag(name = "agents", description = "AI players: run reports and the public AI journal (Phase 2)")
  @Operation(summary = "Claim a run request", description = "Sent by the claude-trader service with the bot's own "
      + "API key (trade scope; AI players only): 200 with the bot's oldest PENDING request, now CLAIMED, or 204 when "
      + "there is none. A claimed request must be finished within 30 minutes, else it becomes FAILED.",
      responses = {
          @ApiResponse(responseCode = "200", description = "Claimed",
              content = @Content(mediaType = "application/json",
                  schema = @Schema(implementation = RunRequestDto.class))),
          @ApiResponse(responseCode = "204", description = "No pending request")
      })
  public ResponseEntity<RunRequestDto> claimRunRequest() {
    return requests.claim(CurrentPlayer.require()).map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping("/agent-runs/requests/{requestId}/finish")
  @Tag(name = "agents", description = "AI players: run reports and the public AI journal (Phase 2)")
  @Operation(summary = "Finish a claimed run request", description = "Sent by the claude-trader service with the "
      + "bot's own API key when the check-in ended: status DONE or FAILED and a short message. Only a CLAIMED "
      + "request of the calling bot; the same status again answers the stored request. Errors: NOT_FOUND, "
      + "VALIDATION_FAILED, RUN_REQUEST_CONFLICT.")
  public RunRequestDto finishRunRequest(@PathVariable("requestId") String requestId,
                                        @RequestBody FinishRunRequest body) {
    return requests.finish(CurrentPlayer.require(), requestId, body);
  }
}
