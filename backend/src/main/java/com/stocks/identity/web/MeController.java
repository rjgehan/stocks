package com.stocks.identity.web;

import com.stocks.contract.identity.MeQueries;
import com.stocks.contract.identity.dto.ChangePasswordRequest;
import com.stocks.contract.identity.dto.MeDto;
import com.stocks.contract.identity.dto.UpdateMeRequest;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.identity.LoginService;
import com.stocks.identity.PlayerService;
import com.stocks.identity.ProfileService;
import com.stocks.security.PlayerSessions;
import com.stocks.security.RequestMetas;
import com.stocks.security.SessionLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.1 {@code /me}: who the caller is (any channel; MCP's {@code whoami} returns the same {@code Me}), and, on a
 * browser session only, profile and password changes.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "me", description = "The authenticated player")
public class MeController {

  private final MeQueries meQueries;
  private final ProfileService profiles;
  private final PlayerService players;
  private final LoginService logins;
  private final SessionLogin sessionLogin;
  private final PlayerSessions sessions;

  public MeController(MeQueries meQueries, ProfileService profiles, PlayerService players, LoginService logins,
                      SessionLogin sessionLogin, PlayerSessions sessions) {
    this.meQueries = meQueries;
    this.profiles = profiles;
    this.players = players;
    this.logins = logins;
    this.sessionLogin = sessionLogin;
    this.sessions = sessions;
  }

  @GetMapping("/me")
  @Operation(summary = "The authenticated player", description = "Session or API key. authMethod and scopes "
      + "describe this request's credentials.")
  public MeDto getMe() {
    return meQueries.me(CurrentPlayer.require());
  }

  @PatchMapping("/me")
  @Operation(summary = "Update my display name or preferences",
      description = "Session only. Null or missing members stay unchanged; preferences merge over the stored ones. "
          + "Display names: 2-40 of letters, digits, space, _ . -. Errors: VALIDATION_FAILED.")
  public MeDto patchMe(@RequestBody UpdateMeRequest body) {
    PlayerPrincipal who = CurrentPlayer.require();
    profiles.update(who, body);
    return meQueries.me(who);
  }

  @PostMapping("/me/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Change my password",
      description = "Session only. Verifies the current password, sets the new one (at least 10 characters), "
          + "rotates this session's id, ends every other session and revokes all of my API keys (§17.3). Errors: "
          + "BAD_CREDENTIALS, LOGIN_LOCKED, WEAK_PASSWORD.")
  public void changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request,
                             HttpServletResponse response) {
    PlayerPrincipal who = CurrentPlayer.require();
    profiles.changePassword(who, body.currentPassword(), body.newPassword(), RequestMetas.of(request, who));
    HttpSession current = request.getSession(false);
    sessions.deleteOthers(who.playerPublicId(), current == null ? null : current.getId());
    PlayerPrincipal refreshed = players.byId(who.playerId()).map(logins::principal).orElse(who);
    sessionLogin.establish(refreshed, request, response); // new session id, fresh authenticatedAt, new XSRF-TOKEN
  }
}
