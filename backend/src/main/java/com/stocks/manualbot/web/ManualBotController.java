package com.stocks.manualbot.web;

import com.stocks.common.error.ApiException;
import com.stocks.contract.manualbot.dto.ManualBotDto;
import com.stocks.contract.manualbot.dto.ManualExecuteRequest;
import com.stocks.contract.manualbot.dto.ManualReplyRequest;
import com.stocks.contract.manualbot.dto.ManualSessionDto;
import com.stocks.contract.manualbot.dto.UpdateManualBotRequest;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.manualbot.ManualBotService;
import com.stocks.manualbot.ManualBotsProperties;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * §20.8 the manual console of an AI bot, for its owner or the admin. Under {@code /api/v1/me/**}, so session-only
 * (API keys and OAuth tokens get 403, writes need CSRF; route rule in {@code SecurityConfig}). The console reads its
 * sessions through the public {@code /players/{handle}/manual-sessions} routes. The reply body is read through a
 * bounded stream ({@code stocks.manual-bots.max-reply-body-bytes}).
 */
@RestController
@RequestMapping("/api/v1/me/bots")
@Tag(name = "manual bots", description = "The manual console: drive an AI bot by copy and paste (§20.8)")
public class ManualBotController {

  private final ManualBotService manual;
  private final ManualBotsProperties properties;
  private final JsonMapper json;

  public ManualBotController(ManualBotService manual, ManualBotsProperties properties, JsonMapper json) {
    this.manual = manual;
    this.properties = properties;
    this.json = json;
  }

  @GetMapping
  @Operation(summary = "My bots' manual consoles", description = "The console state of every AI bot the signed-in "
      + "person owns, by handle (the shape of GET /me/bots/{handle}/manual).")
  public List<ManualBotDto> getMyManualBots() {
    return manual.mine(CurrentPlayer.require());
  }

  @GetMapping("/{handle}/manual")
  @Operation(summary = "A bot's manual console", description = "The owner (or the admin) only: instructions, the "
      + "bot's status, active API keys and connectors, prompts made today and the console limits. Works for frozen "
      + "bots (writes do not).")
  public ManualBotDto getManualBot(@PathVariable("handle") String handle) {
    return manual.state(CurrentPlayer.require(), handle);
  }

  @PutMapping("/{handle}/manual")
  @Operation(summary = "Save a bot's instructions, model label or name", description = "null = unchanged. "
      + "instructions 0-4000 characters (\"\" clears them; key-like strings are removed); public, part of every "
      + "prompt. aiModel 1-24 characters, shown on the profile. displayName 2-40 characters.")
  public ManualBotDto updateManualBot(@PathVariable("handle") String handle,
                                      @RequestBody UpdateManualBotRequest request, HttpServletRequest servlet) {
    PlayerPrincipal human = CurrentPlayer.require();
    return manual.update(human, handle, request, RequestMetas.of(servlet, human));
  }

  @PostMapping("/{handle}/manual-sessions")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Make a prompt", description = "Builds the prompt for the bot from its live account, the "
      + "market and the owner's instructions (detail shape). At most 50 prompts per bot per ET day (RATE_LIMITED "
      + "with retryAfterS).")
  public ManualSessionDto createManualSession(@PathVariable("handle") String handle) {
    return manual.newSession(CurrentPlayer.require(), handle);
  }

  @PostMapping("/{handle}/manual-sessions/{sessionId}/reply")
  @Operation(summary = "Check a pasted reply", description = "Parses the AI's reply (stocks.manual.v1) and previews "
      + "every row live. Can be repeated until the first execute. At most 20,000 characters.",
      requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
          content = @Content(mediaType = "application/json",
              schema = @Schema(implementation = ManualReplyRequest.class))))
  public ManualSessionDto checkManualReply(@PathVariable("handle") String handle,
                                           @PathVariable("sessionId") String sessionId, HttpServletRequest servlet)
      throws IOException {
    PlayerPrincipal human = CurrentPlayer.require();
    byte[] body = readBounded(servlet, properties.maxReplyBodyBytes());
    ManualReplyRequest request;
    try {
      request = json.readValue(body, ManualReplyRequest.class);
    } catch (JacksonException e) {
      throw new HttpMessageNotReadableException("Malformed reply", e, new ServletServerHttpRequest(servlet));
    }
    return manual.reply(human, handle, sessionId, request == null ? null : request.reply());
  }

  @PostMapping("/{handle}/manual-sessions/{sessionId}/execute")
  @Operation(summary = "Place the ticked rows", description = "Places the ticked rows as the bot: cancels, then "
      + "sells, then buys; posts the summary to the journal once. Idempotent: rows already placed are skipped and a "
      + "retried row replays its order. An empty list only posts the summary.")
  public ManualSessionDto executeManualSession(@PathVariable("handle") String handle,
                                               @PathVariable("sessionId") String sessionId,
                                               @RequestBody ManualExecuteRequest request, HttpServletRequest servlet) {
    PlayerPrincipal human = CurrentPlayer.require();
    return manual.execute(human, handle, sessionId, request, RequestMetas.of(servlet, human));
  }

  /** At most {@code max} bytes of the body; more is {@code VALIDATION_FAILED} (400) on field {@code body}. */
  static byte[] readBounded(HttpServletRequest request, int max) throws IOException {
    if (request.getContentLengthLong() > max) {
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
    return ApiException.validation("body", "The request is larger than " + max + " bytes.");
  }
}
