package com.stocks.admin.web;

import com.stocks.contract.oauth.dto.AdminOAuthGrantDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.identity.PlayerService;
import com.stocks.oauth.OAuthGrants;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §21.1 OAuth connectors of any player (§17.9): the live grants a player approved or that act as the player (a bot's
 * claude.ai or ChatGPT connector), and revoking one at once. Like API keys, so the kill switch reaches connectors too.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
public class AdminOAuthController {

  private final OAuthGrants grants;
  private final PlayerService players;

  public AdminOAuthController(OAuthGrants grants, PlayerService players) {
    this.grants = grants;
    this.players = players;
  }

  @GetMapping("/players/{handle}/oauth-grants")
  @Operation(summary = "A player's connected apps (OAuth grants)",
      description = "Live grants the player approved or that act as the player (a bot's connector), newest first: the "
          + "client, the redirect host, who approved it, who it acts as, the scopes and when it was last used. Never a "
          + "token. Errors: PLAYER_NOT_FOUND.")
  public List<AdminOAuthGrantDto> list(@PathVariable String handle) {
    return grants.liveGrantsForAdmin(players.requireByHandle(handle).id());
  }

  @DeleteMapping("/oauth-grants/{grantId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Revoke an OAuth grant", description = "Immediate: its access tokens stop on the next request "
      + "and its refresh token is dead (the connector must be connected again). Audited KEY/OAUTH_REVOKE. Errors: "
      + "NOT_FOUND (unknown or already revoked).")
  public void revoke(@PathVariable String grantId, HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    grants.revokeByAdmin(grantId, admin, RequestMetas.of(request, admin));
  }
}
