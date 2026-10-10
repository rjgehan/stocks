package com.stocks.security;

import com.stocks.common.events.SessionIdChanged;
import com.stocks.common.events.SessionsRevoked;
import com.stocks.contract.security.PlayerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Starts and ends a human's browser session (§17.3) for {@code identity.web.AuthController}, which verifies the
 * password. Login rotates the session id (fixation), stores the {@link PlayerAuthenticationToken} in the Spring
 * Session JDBC session ({@code stocks_sid}, {@code __Host-stocks_sid} in prod) and issues a fresh {@code XSRF-TOKEN}
 * cookie, so the SPA's next unsafe request already has the rotated token. Logout invalidates the session and also
 * rotates the CSRF cookie, and closes the WebSockets the session opened ({@link SessionsRevoked}).
 */
@Component
public class SessionLogin {

  private final SecurityContextRepository contextRepository;
  private final CsrfTokenRepository csrfTokens;
  private final ApplicationEventPublisher events;
  private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

  public SessionLogin(SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokens,
                      ApplicationEventPublisher events) {
    this.contextRepository = contextRepository;
    this.csrfTokens = csrfTokens;
    this.events = events;
  }

  /** Authenticates the current request and session as {@code principal} (authMethod SESSION). */
  public void establish(PlayerPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
    HttpSession existing = request.getSession(false);
    if (existing != null) {
      String old = existing.getId();
      String renewed = request.changeSessionId();
      events.publishEvent(new SessionIdChanged(old, renewed));
    }
    SecurityContext context = contexts.createEmptyContext();
    context.setAuthentication(new PlayerAuthenticationToken(principal));
    contexts.setContext(context);
    contextRepository.saveContext(context, request, response);
    rotateCsrf(request, response);
  }

  /**
   * Replaces the principal of the current session without rotating anything (§17.1 {@code POST /auth/reauth}: a new
   * {@code authenticatedAt}). The session id and the CSRF token stay, so the SPA's in-flight requests keep working.
   */
  public void refresh(PlayerPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
    SecurityContext context = contexts.createEmptyContext();
    context.setAuthentication(new PlayerAuthenticationToken(principal));
    contexts.setContext(context);
    contextRepository.saveContext(context, request, response);
  }

  /** Ends the session, if any, and clears the request's security context. */
  public void end(HttpServletRequest request, HttpServletResponse response) {
    contexts.clearContext();
    HttpSession session = request.getSession(false);
    if (session != null) {
      String id = session.getId();
      session.invalidate();
      events.publishEvent(SessionsRevoked.one(null, id));
    }
    rotateCsrf(request, response);
  }

  /** Issues a new {@code XSRF-TOKEN} cookie (the raw value the SPA echoes as {@code X-XSRF-TOKEN}). */
  public void rotateCsrf(HttpServletRequest request, HttpServletResponse response) {
    CsrfToken token = csrfTokens.generateToken(request);
    csrfTokens.saveToken(token, request, response);
  }
}
