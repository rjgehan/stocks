package com.stocks.admin.web;

import com.stocks.admin.AdminPlayerService;
import com.stocks.contract.identity.dto.ApiKeyCreatedDto;
import com.stocks.contract.identity.dto.ApiKeyInfoDto;
import com.stocks.contract.identity.dto.CreateApiKeyRequest;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.identity.ApiKeyService;
import com.stocks.identity.PlayerService;
import com.stocks.security.RecentAuth;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §21.1 API keys of any player, mostly bots: {@code stk_<keyId>_<secret>}, peppered HMAC, scopes (all four by default
 * for an AI player; never an admin scope). Issuing needs a recent password check; revoking is immediate.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
public class AdminKeyController {

  private final AdminPlayerService service;
  private final ApiKeyService apiKeys;
  private final PlayerService players;

  public AdminKeyController(AdminPlayerService service, ApiKeyService apiKeys, PlayerService players) {
    this.service = service;
    this.apiKeys = apiKeys;
    this.players = players;
  }

  @GetMapping("/players/{handle}/api-keys")
  @Operation(summary = "A player's API keys", description = "Newest first, revoked ones included. Never a secret.")
  public List<ApiKeyInfoDto> list(@PathVariable String handle) {
    return apiKeys.list(players.requireByHandle(handle).id());
  }

  @PostMapping("/players/{handle}/api-keys")
  @ResponseStatus(HttpStatus.CREATED)
  @RecentAuth
  @Operation(summary = "Issue an API key for an AI player",
      description = "Recent password check. AI players only: a human's keys are personal (Settings > API keys), so "
          + "the admin never holds a token that reads a friend's live account (§16.7). scopes default to all four; "
          + "expiresInDays 1-365 or omitted. The token and the Claude Code, Claude API, OpenAI Responses and curl "
          + "snippets are shown once. Errors: PLAYER_NOT_FOUND, VALIDATION_FAILED, FORBIDDEN (a human).")
  public ApiKeyCreatedDto issue(@PathVariable String handle, @Valid @RequestBody CreateApiKeyRequest body,
                                HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    return service.issueKey(handle, body, admin, RequestMetas.of(request, admin));
  }

  @DeleteMapping("/api-keys/{keyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Revoke an API key", description = "Immediate (the key cache is evicted); the bot then gets "
      + "API_KEY_REVOKED. Errors: NOT_FOUND.")
  public void revoke(@PathVariable String keyId, HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    service.revokeKey(keyId, admin, RequestMetas.of(request, admin));
  }
}
