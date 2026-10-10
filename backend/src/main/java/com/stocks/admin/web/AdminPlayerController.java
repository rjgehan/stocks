package com.stocks.admin.web;

import com.stocks.admin.AdminPlayerService;
import com.stocks.admin.AdminPlayerViews;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.web.IdempotencyKeys;
import com.stocks.contract.admin.dto.AdminPlayerDetailDto;
import com.stocks.contract.admin.dto.AdminPlayerDto;
import com.stocks.contract.admin.dto.AdminPlayerLiveViewDto;
import com.stocks.contract.admin.dto.AdminResetRequest;
import com.stocks.contract.admin.dto.CreatePlayerRequest;
import com.stocks.contract.admin.dto.CreatePlayerResponse;
import com.stocks.contract.admin.dto.FreezePlayerRequest;
import com.stocks.contract.admin.dto.LiveViewRequest;
import com.stocks.contract.admin.dto.SetPasswordRequest;
import com.stocks.contract.admin.dto.UpdatePlayerRequest;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.identity.PlayerService;
import com.stocks.security.RecentAuth;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §21.1 admin players: list and detail (never live positions or orders), the audited break-glass live view, create
 * (human or AI, funded account, default watchlist, optional bot key), update, freeze/unfreeze (§10.5), password reset
 * and account reset. Session + ADMIN + CSRF (chain 1); writes marked R need a recent password check.
 */
@RestController
@RequestMapping("/api/v1/admin/players")
@Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
public class AdminPlayerController {

  private final AdminPlayerService service;
  private final AdminPlayerViews views;
  private final PlayerService players;

  public AdminPlayerController(AdminPlayerService service, AdminPlayerViews views, PlayerService players) {
    this.service = service;
    this.views = views;
    this.players = players;
  }

  @GetMapping
  @Operation(summary = "Every player", description = "Sorted by handle, valued live (equity, P&L, return), with open "
      + "orders, live keys, restriction and last seen.")
  public List<AdminPlayerDto> list(@RequestParam(required = false) PlayerKind kind,
                                   @RequestParam(required = false) PlayerStatus status) {
    return views.list(kind, status);
  }

  @GetMapping("/{handle}")
  @Operation(summary = "One player", description = "Runs, balances, end-of-day holdings, transfers, API keys and "
      + "recent audit rows. Never live positions or orders (break-glass only). Errors: PLAYER_NOT_FOUND.")
  public AdminPlayerDetailDto detail(@PathVariable String handle) {
    return views.detail(players.requireByHandle(handle).id());
  }

  @PostMapping("/{handle}/live-view")
  @RecentAuth
  @Operation(summary = "Break glass: the player's live positions and open orders",
      description = "Recent password check. Writes an audit row and an ADMIN_VIEW entry the player sees in their "
          + "activity before showing anything. reason: 5-200 characters. Errors: PLAYER_NOT_FOUND, "
          + "ACCOUNT_NOT_FOUND.")
  public AdminPlayerLiveViewDto liveView(@PathVariable String handle, @Valid @RequestBody LiveViewRequest body,
                                         HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    return service.liveView(handle, body.reason(), admin, RequestMetas.of(request, admin));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @RecentAuth
  @Operation(summary = "Create a human or AI player",
      description = "Recent password check. Opens the funded CASH account (startingCash, default the league's) and "
          + "the default watchlist. HUMAN: password required. AI: aiModel required, ownerHandle defaults to you, "
          + "never an admin, and an all-scope key is issued unless issueKey is false (token shown once). Errors: "
          + "HANDLE_TAKEN, VALIDATION_FAILED, WEAK_PASSWORD.")
  public CreatePlayerResponse create(@Valid @RequestBody CreatePlayerRequest body, HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    return service.create(body, admin, RequestMetas.of(request, admin));
  }

  @PatchMapping("/{handle}")
  @RecentAuth
  @Operation(summary = "Update a player",
      description = "Recent password check. Null members stay unchanged. status ACTIVE or DISABLED (disabling ends "
          + "sessions and freezes the account; FROZEN goes through /freeze); role ADMIN only for a human (ends the "
          + "player's sessions); apiTradingAllowed false removes 'trade' from their personal keys. You cannot "
          + "disable or demote yourself. Errors: PLAYER_NOT_FOUND, FORBIDDEN, VALIDATION_FAILED.")
  public AdminPlayerDto update(@PathVariable String handle, @RequestBody UpdatePlayerRequest body,
                               HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    return service.update(handle, body, admin, RequestMetas.of(request, admin));
  }

  @PostMapping("/{handle}/freeze")
  @RecentAuth
  @Operation(summary = "Freeze a player (kill switch)",
      description = "Recent password check. Status FROZEN at once (sessions ended, caches evicted, keys revoked only "
          + "with revokeKeys), then the engine cancels open orders and restricts the account. A frozen bot gets "
          + "PLAYER_FROZEN. reason: 1-80 characters.")
  public AdminPlayerDto freeze(@PathVariable String handle, @Valid @RequestBody FreezePlayerRequest body,
                               HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    return service.freeze(handle, body, admin, RequestMetas.of(request, admin));
  }

  @PostMapping("/{handle}/unfreeze")
  @RecentAuth
  @Operation(summary = "Unfreeze a player", description = "Recent password check. Status ACTIVE; keys that were not "
      + "revoked work again; the account's restriction becomes NONE or the recomputed GFV restriction.")
  public AdminPlayerDto unfreeze(@PathVariable String handle, HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    return service.unfreeze(handle, admin, RequestMetas.of(request, admin));
  }

  @PostMapping("/{handle}/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RecentAuth
  @Operation(summary = "Set a human's password", description = "Recent password check. Ends their sessions and "
      + "revokes their API keys. Errors: WEAK_PASSWORD, VALIDATION_FAILED (AI players have no password).")
  public void setPassword(@PathVariable String handle, @Valid @RequestBody SetPasswordRequest body,
                          HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    service.setPassword(handle, body.newPassword(), admin, RequestMetas.of(request, admin));
  }

  @PostMapping("/{handle}/reset")
  @RecentAuth
  @Operation(summary = "Reset a player's account (new run)",
      description = "Recent password check and an Idempotency-Key (1-80 of A-Z a-z 0-9 . _ : -; reuse it to retry). "
          + "Closes the run at its live value and opens run n+1 at the final unit value, so a reset never erases a "
          + "loss. startingCash defaults to the league's; memo 1-200 characters. A replay answers the stored result "
          + "with Idempotent-Replay: true. Errors: IDEMPOTENCY_KEY_CONFLICT, PLAYER_NOT_FOUND, VALIDATION_FAILED.")
  public ResponseEntity<AccountDto> reset(@PathVariable String handle, @Valid @RequestBody AdminResetRequest body,
                                          @Parameter(description = "Unique per reset; reuse it only to retry")
                                          @RequestHeader(name = IdempotencyKeys.HEADER, required = false)
                                          String idempotencyKey, HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    return service.reset(handle, body, idempotencyKey, admin, RequestMetas.of(request, admin));
  }
}
