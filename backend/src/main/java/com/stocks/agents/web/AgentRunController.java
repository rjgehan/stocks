package com.stocks.agents.web;

import com.stocks.agents.AgentRunService;
import com.stocks.agents.AgentsProperties;
import com.stocks.common.error.ApiException;
import com.stocks.contract.agents.dto.AgentRunDto;
import com.stocks.contract.agents.dto.AgentRunReportRequest;
import com.stocks.contract.agents.dto.AgentRunSummaryDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * §20.6 agent runs: the runner reports each run with the bot's own key ({@code trade} scope, AI players only; route
 * rule in {@code SecurityConfig}), and every player reads a bot's public activity summary ({@code league:read}).
 * The report body is read through a bounded stream ({@code stocks.agents.max-report-bytes}, 32 KiB), so neither a
 * large {@code Content-Length} nor a chunked body gets past it.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "agents", description = "AI players: run reports and the public AI journal (Phase 2)")
public class AgentRunController {

  private final AgentRunService runs;
  private final AgentsProperties properties;
  private final JsonMapper json;

  public AgentRunController(AgentRunService runs, AgentsProperties properties, JsonMapper json) {
    this.runs = runs;
    this.properties = properties;
    this.json = json;
  }

  @PostMapping("/agent-runs")
  @Operation(summary = "Report an agent run",
      description = "Sent by the agent runner after every run (skipped ones included) with the bot's own API key "
          + "(trade scope; AI players only). The run is recorded for the key's player. The same runRef again "
          + "answers 200 with the stored run. Body at most 32 KiB.",
      requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
          content = @Content(mediaType = "application/json",
              schema = @Schema(implementation = AgentRunReportRequest.class))),
      responses = {
          @ApiResponse(responseCode = "201", description = "Recorded",
              content = @Content(mediaType = "application/json", schema = @Schema(implementation = AgentRunDto.class))),
          @ApiResponse(responseCode = "200", description = "Already recorded (same runRef)",
              content = @Content(mediaType = "application/json", schema = @Schema(implementation = AgentRunDto.class)))
      })
  public ResponseEntity<AgentRunDto> reportAgentRun(HttpServletRequest request) throws IOException {
    PlayerPrincipal caller = CurrentPlayer.require();
    AgentRunService.requireBotKey(caller);
    byte[] body = readBounded(request, properties.maxReportBytes());
    AgentRunReportRequest report;
    try {
      report = json.readValue(body, AgentRunReportRequest.class);
    } catch (JacksonException e) {
      throw new HttpMessageNotReadableException("Malformed run report", e, new ServletServerHttpRequest(request));
    }
    AgentRunService.Reported result = runs.report(caller, report);
    return ResponseEntity.status(result.created() ? 201 : 200).body(result.run());
  }

  @GetMapping("/players/{handle}/agent-runs")
  @Operation(summary = "A bot's run activity", description = "The public Bot activity card of a profile: runs in "
      + "total and in the last 7 days, and the last run's time, status, orders and tool calls. No cost and no "
      + "message (admin only). Zeros and nulls for a player without runs.")
  public AgentRunSummaryDto getPlayerAgentRuns(@PathVariable("handle") String handle) {
    return runs.summary(handle);
  }

  /** At most {@code max} bytes of the body; more is {@code VALIDATION_FAILED} (400). */
  static byte[] readBounded(HttpServletRequest request, int max) throws IOException {
    long declared = request.getContentLengthLong();
    if (declared > max) {
      throw tooLarge(max);
    }
    try (InputStream in = request.getInputStream()) {
      byte[] buf = in.readNBytes(max + 1);
      if (buf.length > max) {
        throw tooLarge(max);
      }
      return buf;
    }
  }

  private static ApiException tooLarge(int max) {
    return ApiException.validation("body", "The report is larger than " + max + " bytes.");
  }
}
