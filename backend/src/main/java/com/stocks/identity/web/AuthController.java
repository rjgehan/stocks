package com.stocks.identity.web;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.contract.identity.dto.LoginRequest;
import com.stocks.contract.identity.dto.MeDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.identity.LoginService;
import com.stocks.identity.MeService;
import com.stocks.security.RequestMetas;
import com.stocks.security.SessionLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.1 human authentication for the SPA: CSRF bootstrap, JSON login and logout. Sessions are Spring Session JDBC
 * ({@code stocks_sid}; {@code __Host-stocks_sid} in prod); CSRF is the {@code XSRF-TOKEN} cookie echoed as
 * {@code X-XSRF-TOKEN} (§17.3). Bots never log in: they send {@code Authorization: Bearer stk_…}.
 * TODO(WS1, Phase 1): {@code POST /auth/signup} (invites) and {@code POST /auth/reauth} ({@code @RecentAuth}).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth", description = "Browser session login (§17.3)")
public class AuthController {

  private final LoginService loginService;
  private final SessionLogin sessionLogin;
  private final MeService meService;
  private final AuditService audit;

  public AuthController(LoginService loginService, SessionLogin sessionLogin, MeService meService,
                        AuditService audit) {
    this.loginService = loginService;
    this.sessionLogin = sessionLogin;
    this.meService = meService;
    this.audit = audit;
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
          + "BAD_CREDENTIALS, LOGIN_LOCKED, PLAYER_DISABLED.")
  public MeDto login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                     HttpServletResponse response) {
    PlayerPrincipal principal = loginService.authenticate(body.handle(), body.password(),
        RequestMetas.of(request, null));
    sessionLogin.establish(principal, request, response);
    return meService.me(principal);
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
}
