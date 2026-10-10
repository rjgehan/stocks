package com.stocks.identity.web;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.contract.identity.dto.LoginRequest;
import com.stocks.contract.identity.dto.MeDto;
import com.stocks.contract.identity.dto.ReauthRequest;
import com.stocks.contract.identity.dto.SignupRequest;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.identity.AccountProvisioner;
import com.stocks.identity.LoginService;
import com.stocks.identity.MeService;
import com.stocks.identity.SignupService;
import com.stocks.security.RequestMetas;
import com.stocks.security.SessionLogin;
import com.stocks.security.SessionPasswordGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.1 human authentication for the SPA: CSRF bootstrap, JSON login and logout, invite-only signup and the
 * {@code @RecentAuth} re-check. Sessions are Spring Session JDBC ({@code stocks_sid}; {@code __Host-stocks_sid} in
 * prod); CSRF is the {@code XSRF-TOKEN} cookie echoed as {@code X-XSRF-TOKEN} (§17.3). Bots never log in: they send
 * {@code Authorization: Bearer stk_…}.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth", description = "Browser session login (§17.3)")
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final LoginService loginService;
  private final SignupService signupService;
  private final SessionLogin sessionLogin;
  private final MeService meService;
  private final AccountProvisioner accounts;
  private final AuditService audit;
  private final SessionPasswordGuard passwordGuard;

  public AuthController(LoginService loginService, SignupService signupService, SessionLogin sessionLogin,
                        MeService meService, AccountProvisioner accounts, AuditService audit,
                        SessionPasswordGuard passwordGuard) {
    this.loginService = loginService;
    this.signupService = signupService;
    this.sessionLogin = sessionLogin;
    this.meService = meService;
    this.accounts = accounts;
    this.audit = audit;
    this.passwordGuard = passwordGuard;
  }

  @GetMapping("/csrf")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @SecurityRequirements
  @Operation(summary = "Issue the XSRF-TOKEN cookie",
      description = "Sets the XSRF-TOKEN cookie. Send its raw value as X-XSRF-TOKEN on every unsafe request.")
  public void getCsrf(HttpServletRequest request) {
    CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    if (token != null) {
      token.getToken(); // resolving the deferred token writes the cookie
    }
  }

  @PostMapping("/login")
  @SecurityRequirements
  @Operation(summary = "Log in with handle and password",
      description = "Humans only. Starts a session (stocks_sid cookie), rotates XSRF-TOKEN and returns Me. Errors: "
          + "BAD_CREDENTIALS, LOGIN_LOCKED (10 failures of one handle from one IP, or 100 from anywhere, lock it for "
          + "15 minutes), PLAYER_DISABLED. Limited to 10 attempts per minute per IP.")
  public MeDto login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                     HttpServletResponse response) {
    PlayerPrincipal principal = loginService.authenticate(body.handle(), body.password(),
        RequestMetas.of(request, null));
    sessionLogin.establish(principal, request, response);
    selfHealAccount(principal);
    return meService.me(principal);
  }

  @PostMapping("/signup")
  @ResponseStatus(HttpStatus.CREATED)
  @SecurityRequirements
  @Operation(summary = "Join the league with an invite",
      description = "Invite-only. Creates a HUMAN player (handle ^[a-z0-9_]{2,32}$, lower-cased; display name 2-40; "
          + "password at least 10 characters), opens the CASH account with the league's starting cash and the "
          + "\"My List\" watchlist, logs the new player in (session cookie, rotated XSRF-TOKEN) and returns Me. "
          + "Errors: INVITE_INVALID, HANDLE_TAKEN, WEAK_PASSWORD, VALIDATION_FAILED. Limited to 5 per hour per IP.")
  public MeDto signup(@Valid @RequestBody SignupRequest body, HttpServletRequest request,
                      HttpServletResponse response) {
    PlayerPrincipal principal = signupService.signup(body, RequestMetas.of(request, null));
    sessionLogin.establish(principal, request, response);
    return meService.me(principal);
  }

  @PostMapping("/reauth")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Confirm the password again",
      description = "Session only. Refreshes the session's authenticatedAt, which @RecentAuth actions (cash "
          + "transfers, resets, freeze, key issuance, role changes, break-glass view) require to be at most 15 "
          + "minutes old (RECENT_AUTH_REQUIRED otherwise). Never locked by failed logins from elsewhere; 5 wrong "
          + "passwords end this session (UNAUTHENTICATED). Errors: BAD_CREDENTIALS, UNAUTHENTICATED.")
  public void reauth(@Valid @RequestBody ReauthRequest body, HttpServletRequest request,
                     HttpServletResponse response) {
    PlayerPrincipal current = CurrentPlayer.require();
    PlayerPrincipal refreshed = passwordGuard.check(request, response,
        () -> loginService.reauthenticate(current, body.password(), RequestMetas.of(request, current)));
    sessionLogin.refresh(refreshed, request, response);
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "End the session", description = "Session only. Invalidates the session and rotates "
      + "XSRF-TOKEN.")
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    PlayerPrincipal principal = CurrentPlayer.current().orElse(null);
    audit.record(AuditEntry.of(AuditCategory.AUTH, "LOGOUT", AuditOutcome.OK).withActor(principal)
        .withMeta(RequestMetas.of(request, principal)));
    sessionLogin.end(request, response);
  }

  /** A human whose first account was never opened (engine down at signup) gets it at the next login. */
  private void selfHealAccount(PlayerPrincipal principal) {
    try {
      accounts.ensureAccount(principal.playerId(), principal.playerId());
    } catch (RuntimeException e) {
      log.warn("Could not ensure the account of '{}' at login: {}", principal.handle(), e.toString());
    }
  }
}
