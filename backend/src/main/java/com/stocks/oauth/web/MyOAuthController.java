package com.stocks.oauth.web;

import com.stocks.common.domain.Channel;
import com.stocks.contract.oauth.dto.ConnectedAppDto;
import com.stocks.contract.oauth.dto.OAuthConsentDto;
import com.stocks.contract.oauth.dto.OAuthDecisionRequest;
import com.stocks.contract.oauth.dto.OAuthRedirectDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.oauth.ConsentService;
import com.stocks.oauth.OAuthGrants;
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
 * §17.9.1 the signed-in human's side of OAuth: the act-as consent screen ({@code /oauth/consent}) and Settings →
 * Connected apps. Session only (chain 2 {@code /me/**}); writes need CSRF.
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "me", description = "The authenticated player")
public class MyOAuthController {

  private final ConsentService consent;
  private final OAuthGrants grants;

  public MyOAuthController(ConsentService consent, OAuthGrants grants) {
    this.consent = consent;
    this.grants = grants;
  }

  @GetMapping("/oauth/requests/{requestId}")
  @Operation(summary = "An OAuth authorization request waiting for my consent",
      description = "Session only. The consent screen of a claude.ai / ChatGPT connector: the client, its redirect "
          + "host, the requested scopes and the 'Act as' choices (your active AI bots, then yourself) with the scopes "
          + "each may get. Errors: NOT_FOUND (unknown, used or expired; requests live 15 minutes), FORBIDDEN, "
          + "PLAYER_FROZEN, PLAYER_DISABLED.")
  public OAuthConsentDto oauthRequest(@PathVariable String requestId) {
    return consent.view(requestId, CurrentPlayer.require());
  }

  @PostMapping("/oauth/requests/{requestId}")
  @Operation(summary = "Approve or deny an OAuth authorization request",
      description = "Session only, CSRF. approve=true needs actAs (a handle from the request's actors) and optionally "
          + "scopes (default: every requested scope the actor may get). Answers the URL to send the browser to: the "
          + "client's redirect URI with code, state and iss, or error=access_denied. The request is used up either way. "
          + "Approving needs a recent password check (like issuing an API key: RECENT_AUTH_REQUIRED, then "
          + "POST /auth/reauth and retry); denying does not. "
          + "Errors: NOT_FOUND, VALIDATION_FAILED, FORBIDDEN, PLAYER_FROZEN, PLAYER_DISABLED, RECENT_AUTH_REQUIRED.")
  public OAuthRedirectDto decideOAuthRequest(@PathVariable String requestId, @Valid @RequestBody OAuthDecisionRequest body,
                                 HttpServletRequest request) {
    PlayerPrincipal who = CurrentPlayer.require();
    return consent.decide(requestId, body, who, OAuthRequests.meta(request, Channel.WEB, null));
  }

  @GetMapping("/connected-apps")
  @Operation(summary = "My connected apps",
      description = "Session only. The live OAuth grants you approved (claude.ai, ChatGPT, Claude Code, ...), newest "
          + "first, with the player each acts as and its scopes.")
  public List<ConnectedAppDto> connectedApps() {
    return grants.connectedApps(CurrentPlayer.require().playerId());
  }

  @DeleteMapping("/connected-apps/{grantId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Disconnect an app",
      description = "Session only, CSRF. Revokes the grant at once: its refresh token stops working and its access "
          + "tokens are refused on the next request. Errors: NOT_FOUND.")
  public void disconnectApp(@PathVariable String grantId, HttpServletRequest request) {
    PlayerPrincipal who = CurrentPlayer.require();
    grants.revokeByUser(grantId, who, OAuthRequests.meta(request, Channel.WEB, null));
  }
}
